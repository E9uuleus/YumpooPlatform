using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

// Compiled by the short-lived Windows PowerShell launcher; no installed native dependency is needed.
public static class YumpooProcessTree
{
    public static int Run(string executable, string arguments)
    {
        IntPtr job = CreateJobObjectW(IntPtr.Zero, null);
        if (job == IntPtr.Zero) throw Failure("CreateJobObjectW");
        ProcessInformation process = new ProcessInformation();
        bool assigned = false;
        try
        {
            ExtendedLimitInformation limits = new ExtendedLimitInformation();
            // The handle is not inherited. Killing the launcher on timeout also terminates its descendants.
            limits.BasicLimitInformation.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if (!SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf(limits)))
                throw Failure("SetInformationJobObject");

            StartupInfo startup = new StartupInfo();
            startup.cb = (uint)Marshal.SizeOf(startup);
            startup.dwFlags = 1; // STARTF_USESHOWWINDOW, with SW_HIDE (0)
            StringBuilder commandLine = new StringBuilder("\"" + executable + "\" " + arguments);
            // Register the root before it can create children or exit. Start-Process -Wait on Windows
            // PowerShell 5.1 registers an already-running process and can miss this entire tree.
            if (!CreateProcessW(executable, commandLine, IntPtr.Zero, IntPtr.Zero, false,
                0x08000004, IntPtr.Zero, null, ref startup, out process)) // CREATE_NO_WINDOW | CREATE_SUSPENDED
                throw Failure("CreateProcessW");
            if (!AssignProcessToJobObject(job, process.hProcess))
                throw Failure("AssignProcessToJobObject");
            assigned = true;
            if (ResumeThread(process.hThread) == uint.MaxValue) throw Failure("ResumeThread");

            BasicAccountingInformation accounting;
            do
            {
                if (!QueryInformationJobObject(job, 1, out accounting,
                    (uint)Marshal.SizeOf(typeof(BasicAccountingInformation)), IntPtr.Zero))
                    throw Failure("QueryInformationJobObject");
                if (accounting.ActiveProcesses != 0) Thread.Sleep(50);
            } while (accounting.ActiveProcesses != 0);

            uint exitCode;
            if (!GetExitCodeProcess(process.hProcess, out exitCode)) throw Failure("GetExitCodeProcess");
            return unchecked((int)exitCode);
        }
        finally
        {
            // Assignment failure must not leave a suspended root behind. After assignment, closing the
            // job cleans up every descendant on any failure; successful completion has no members left.
            if (!assigned && process.hProcess != IntPtr.Zero) TerminateProcess(process.hProcess, 1);
            CloseHandle(job);
            if (process.hThread != IntPtr.Zero) CloseHandle(process.hThread);
            if (process.hProcess != IntPtr.Zero) CloseHandle(process.hProcess);
        }
    }

    private static Win32Exception Failure(string operation)
    {
        int code = Marshal.GetLastWin32Error();
        return new Win32Exception(code, operation + ": " + new Win32Exception(code).Message);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct StartupInfo
    {
        public uint cb;
        public IntPtr lpReserved, lpDesktop, lpTitle;
        public uint dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public ushort wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInformation
    {
        public IntPtr hProcess, hThread;
        public uint dwProcessId, dwThreadId;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BasicLimitInformation
    {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
        public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ExtendedLimitInformation
    {
        public BasicLimitInformation BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BasicAccountingInformation
    {
        public long TotalUserTime, TotalKernelTime, ThisPeriodTotalUserTime, ThisPeriodTotalKernelTime;
        public uint TotalPageFaultCount, TotalProcesses, ActiveProcesses, TotalTerminatedProcesses;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObjectW(IntPtr attributes, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetInformationJobObject(IntPtr job, int informationClass,
        ref ExtendedLimitInformation information, uint length);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateProcessW(string application, StringBuilder commandLine,
        IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint flags,
        IntPtr environment, string currentDirectory, ref StartupInfo startup, out ProcessInformation process);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint ResumeThread(IntPtr thread);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool QueryInformationJobObject(IntPtr job, int informationClass,
        out BasicAccountingInformation information, uint length, IntPtr returnLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetExitCodeProcess(IntPtr process, out uint exitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool TerminateProcess(IntPtr process, uint exitCode);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);
}
