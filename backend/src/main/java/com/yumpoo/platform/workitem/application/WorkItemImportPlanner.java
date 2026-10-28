package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.WorkItemImportModels.DuplicateMode;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Field;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Issue;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Outcome;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Pure planner shared by the dry run and the commit of a work item import. */
public final class WorkItemImportPlanner {
    static final int MAX_TITLE_LENGTH = 300;

    private WorkItemImportPlanner() {}

    public record Existing(UUID id, UUID contentId, String itemNo, String title,
            String statusCode, String priority, List<UUID> assigneeUserIds) {
        public Existing {
            assigneeUserIds = assigneeUserIds == null ? List.of() : List.copyOf(assigneeUserIds);
        }
    }

    public record ParentLink(UUID parentId, UUID childId) {}

    /** Values a row may legitimately carry: active categories, statuses, priorities and members. */
    public record Catalog(Set<UUID> contentIds, Set<String> statusCodes,
            Set<String> priorityCodes, Set<UUID> assigneeIds) {}

    public record Input(DuplicateMode mode, Set<Field> fields, List<Row> rows,
            List<Existing> existing, List<ParentLink> links, Catalog catalog) {}

    /** A work item the plan refers to: an existing one, or the item a CREATE row will make. */
    public record Ref(UUID existingId, int rowIndex) {
        static Ref existing(UUID id) { return new Ref(id, -1); }
        static Ref row(int index) { return new Ref(null, index); }
    }

    /** KEEP leaves the relation alone, SET attaches {@code parent}, CLEAR detaches the current parent. */
    public enum ParentMode { KEEP, SET, CLEAR }

    public record PlannedRow(int index, Row row, String title, Outcome outcome, Existing target,
            List<Issue> issues, ParentMode parentMode, Ref parent) {}

    public record Plan(List<PlannedRow> rows) {
        public long count(Outcome outcome) {
            return rows.stream().filter(row -> row.outcome() == outcome).count();
        }
    }

    public static String normalizePriority(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    public static Plan plan(Input input) {
        return new Run(input).plan();
    }

    private static final class Run {
        private final Input in;
        private final int size;
        private final String[] titles;
        private final Outcome[] outcome;
        private final Existing[] target;
        private final List<List<Issue>> issues = new ArrayList<>();
        private final ParentMode[] mode;
        private final Ref[] parent;
        private final Map<String, List<Existing>> existingByTitle = new HashMap<>();
        private final Map<UUID, UUID> dbParent = new HashMap<>();
        private final Set<UUID> dbParents = new HashSet<>();
        private final Map<UUID, Integer> updateRowByExisting = new HashMap<>();
        private final boolean parentMapped;

        Run(Input in) {
            this.in = in;
            this.size = in.rows().size();
            this.titles = new String[size];
            this.outcome = new Outcome[size];
            this.target = new Existing[size];
            this.mode = new ParentMode[size];
            this.parent = new Ref[size];
            this.parentMapped = in.fields().contains(Field.PARENT);
            for (int i = 0; i < size; i++) issues.add(new ArrayList<>());
            for (Existing existing : in.existing())
                existingByTitle.computeIfAbsent(existing.title().strip(), key -> new ArrayList<>())
                        .add(existing);
            for (ParentLink link : in.links()) {
                dbParent.put(link.childId(), link.parentId());
                dbParents.add(link.parentId());
            }
        }

        Plan plan() {
            classify();
            resolveParents();
            List<PlannedRow> planned = new ArrayList<>(size);
            for (int i = 0; i < size; i++)
                planned.add(new PlannedRow(i, in.rows().get(i), titles[i], outcome[i], target[i],
                        List.copyOf(issues.get(i)), mode[i], parent[i]));
            return new Plan(List.copyOf(planned));
        }

        private void classify() {
            Map<String, Integer> fileCounts = new HashMap<>();
            for (int i = 0; i < size; i++) {
                String title = in.rows().get(i).title() == null ? "" : in.rows().get(i).title().strip();
                titles[i] = title;
                mode[i] = ParentMode.KEEP;
                if (validTitle(title)) fileCounts.merge(title, 1, Integer::sum);
            }
            for (int i = 0; i < size; i++) {
                if (!validTitle(titles[i])) {
                    error(i, "TITLE_INVALID", null);
                    continue;
                }
                List<Existing> matches = existingByTitle.getOrDefault(titles[i], List.of());
                switch (in.mode()) {
                    case CREATE -> outcome[i] = Outcome.CREATE;
                    case SKIP -> {
                        if (matches.isEmpty()) outcome[i] = Outcome.CREATE;
                        else {
                            outcome[i] = Outcome.SKIP;
                            if (matches.size() == 1) target[i] = matches.getFirst();
                            issues.get(i).add(new Issue("DUPLICATE_EXISTS", matches.stream()
                                    .map(Existing::itemNo).collect(Collectors.joining(","))));
                        }
                    }
                    case UPDATE -> {
                        if (matches.isEmpty()) outcome[i] = Outcome.CREATE;
                        else if (fileCounts.get(titles[i]) > 1) error(i, "DUPLICATE_IN_FILE",
                                String.valueOf(fileCounts.get(titles[i])));
                        else if (matches.size() > 1) error(i, "MATCH_AMBIGUOUS",
                                String.valueOf(matches.size()));
                        else {
                            outcome[i] = Outcome.UPDATE;
                            target[i] = matches.getFirst();
                            updateRowByExisting.put(target[i].id(), i);
                        }
                    }
                }
                if (outcome[i] == Outcome.CREATE || outcome[i] == Outcome.UPDATE) validate(i);
            }
        }

        private void validate(int i) {
            Row row = in.rows().get(i);
            Existing existing = outcome[i] == Outcome.UPDATE ? target[i] : null;
            boolean all = existing == null;
            Catalog catalog = in.catalog();
            if (all || in.fields().contains(Field.CONTENT)) {
                if (row.contentId() == null || !(catalog.contentIds().contains(row.contentId())
                        || (existing != null && row.contentId().equals(existing.contentId()))))
                    issue(i, "CONTENT_INVALID", row.contentId() == null ? null : row.contentId().toString());
            }
            if (all || in.fields().contains(Field.STATUS)) {
                String status = row.statusCode() == null ? "" : row.statusCode().strip();
                if (!(catalog.statusCodes().contains(status)
                        || (existing != null && status.equals(existing.statusCode()))))
                    issue(i, "STATUS_INVALID", status.isEmpty() ? null : status);
            }
            if (all || in.fields().contains(Field.PRIORITY)) {
                String priority = normalizePriority(row.priority());
                if (priority != null && !(catalog.priorityCodes().contains(priority)
                        || (existing != null && priority.equals(existing.priority()))))
                    issue(i, "PRIORITY_INVALID", priority);
            }
            if (all || in.fields().contains(Field.ASSIGNEES)) {
                List<UUID> unknown = row.assigneeUserIds().stream().filter(id ->
                        !(catalog.assigneeIds().contains(id)
                                || (existing != null && existing.assigneeUserIds().contains(id)))).toList();
                if (!unknown.isEmpty()) issue(i, "ASSIGNEE_INVALID", unknown.stream()
                        .map(UUID::toString).collect(Collectors.joining(",")));
            }
            if (!issues.get(i).isEmpty()) outcome[i] = Outcome.ERROR;
        }

        private void resolveParents() {
            if (!parentMapped) return;
            Map<String, Set<Ref>> fileRefs = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) {
                switch (outcome[i]) {
                    case CREATE -> fileRefs.computeIfAbsent(titles[i], key -> new LinkedHashSet<>())
                            .add(Ref.row(i));
                    case UPDATE -> fileRefs.computeIfAbsent(titles[i], key -> new LinkedHashSet<>())
                            .add(Ref.existing(target[i].id()));
                    case SKIP -> {
                        for (Existing existing : existingByTitle.getOrDefault(titles[i], List.of()))
                            fileRefs.computeIfAbsent(titles[i], key -> new LinkedHashSet<>())
                                    .add(Ref.existing(existing.id()));
                    }
                    case ERROR -> { }
                }
            }
            for (int i = 0; i < size; i++) {
                if (!processed(i)) continue;
                boolean update = outcome[i] == Outcome.UPDATE;
                String parentTitle = in.rows().get(i).parentTitle() == null ? ""
                        : in.rows().get(i).parentTitle().strip();
                ParentMode detached = update ? ParentMode.CLEAR : ParentMode.KEEP;
                if (parentTitle.isEmpty()) {
                    mode[i] = detached;
                    continue;
                }
                Set<Ref> candidates = fileRefs.containsKey(parentTitle) ? fileRefs.get(parentTitle)
                        : existingByTitle.getOrDefault(parentTitle, List.of()).stream()
                                .map(existing -> Ref.existing(existing.id()))
                                .collect(Collectors.toCollection(LinkedHashSet::new));
                if (candidates.isEmpty()) {
                    issue(i, "PARENT_NOT_FOUND", parentTitle);
                    mode[i] = detached;
                } else if (candidates.size() > 1) {
                    issue(i, "PARENT_AMBIGUOUS", parentTitle);
                    mode[i] = detached;
                } else {
                    mode[i] = ParentMode.SET;
                    parent[i] = candidates.iterator().next();
                }
            }
            for (int i = 0; i < size; i++) {
                if (processed(i) && mode[i] == ParentMode.SET && selfRef(i).equals(parent[i]))
                    detachForCycle(i);
            }
            for (int i = 0; i < size; i++) {
                if (processed(i) && mode[i] == ParentMode.SET && outcome[i] == Outcome.UPDATE
                        && dbParents.contains(target[i].id())) {
                    issue(i, "PARENT_HAS_CHILDREN", in.rows().get(i).parentTitle().strip());
                    mode[i] = ParentMode.KEEP;
                    parent[i] = null;
                }
            }
            breakCycles();
            for (int i = 0; i < size; i++) {
                if (!processed(i) || mode[i] != ParentMode.SET) continue;
                Ref root = parent[i];
                for (Ref above = parentOf(root); above != null; above = parentOf(root)) root = above;
                if (!root.equals(parent[i])) {
                    parent[i] = root;
                    issue(i, "PARENT_FLATTENED", in.rows().get(i).parentTitle().strip());
                }
            }
        }

        private void breakCycles() {
            for (int i = 0; i < size; i++) {
                if (!processed(i) || mode[i] != ParentMode.SET) continue;
                LinkedHashSet<Ref> path = new LinkedHashSet<>();
                Ref current = selfRef(i);
                while (current != null && path.add(current)) current = parentOf(current);
                if (current == null) continue;
                boolean inCycle = false;
                for (Ref node : path) {
                    inCycle |= node.equals(current);
                    if (!inCycle) continue;
                    int row = node.existingId() == null ? node.rowIndex()
                            : updateRowByExisting.getOrDefault(node.existingId(), -1);
                    if (row >= 0 && processed(row) && mode[row] == ParentMode.SET) detachForCycle(row);
                }
            }
        }

        private void detachForCycle(int i) {
            issue(i, "PARENT_CYCLE", in.rows().get(i).parentTitle().strip());
            mode[i] = outcome[i] == Outcome.UPDATE ? ParentMode.CLEAR : ParentMode.KEEP;
            parent[i] = null;
        }

        private Ref parentOf(Ref ref) {
            if (ref.existingId() == null)
                return mode[ref.rowIndex()] == ParentMode.SET ? parent[ref.rowIndex()] : null;
            Integer row = updateRowByExisting.get(ref.existingId());
            if (row != null && processed(row)) {
                return switch (mode[row]) {
                    case SET -> parent[row];
                    case CLEAR -> null;
                    case KEEP -> dbParentRef(ref.existingId());
                };
            }
            return dbParentRef(ref.existingId());
        }

        private Ref dbParentRef(UUID existingId) {
            UUID parentId = dbParent.get(existingId);
            return parentId == null ? null : Ref.existing(parentId);
        }

        private Ref selfRef(int i) {
            return outcome[i] == Outcome.UPDATE ? Ref.existing(target[i].id()) : Ref.row(i);
        }

        private boolean processed(int i) {
            return parentMapped && (outcome[i] == Outcome.CREATE || outcome[i] == Outcome.UPDATE);
        }

        private void error(int i, String code, String detail) {
            outcome[i] = Outcome.ERROR;
            issues.get(i).add(new Issue(code, detail));
        }

        private void issue(int i, String code, String detail) {
            issues.get(i).add(new Issue(code, detail));
        }

        private static boolean validTitle(String title) {
            return !title.isEmpty() && title.length() <= MAX_TITLE_LENGTH;
        }
    }
}
