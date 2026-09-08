export function verificationEnvironment(source) {
  return Object.fromEntries(Object.entries(source).filter(([name]) =>
    !/^(SPRING_|YUMPOO_)/iu.test(name)
    && !/^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|MAVEN_OPTS|MAVEN_ARGS|NODE_OPTIONS)$/iu.test(name)))
}
