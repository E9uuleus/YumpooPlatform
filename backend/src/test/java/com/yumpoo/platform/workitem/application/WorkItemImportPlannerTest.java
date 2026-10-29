package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.WorkItemImportModels.DuplicateMode;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Field;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Issue;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Outcome;
import com.yumpoo.platform.workitem.application.WorkItemImportModels.Row;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.Catalog;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.Existing;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.Input;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.ParentLink;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.ParentMode;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.Plan;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.PlannedRow;
import com.yumpoo.platform.workitem.application.WorkItemImportPlanner.Ref;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkItemImportPlannerTest {
    private static final UUID CONTENT = id(1);
    private static final UUID OTHER_CONTENT = id(2);
    private static final UUID MEMBER = id(3);
    private static final Catalog CATALOG = new Catalog(Set.of(CONTENT, OTHER_CONTENT),
            Set.of("NOT_STARTED", "IN_PROGRESS", "DONE"), Set.of("HIGH", "LOW"), Set.of(MEMBER));

    @Test
    void createModeCreatesEveryRowEvenWhenTheTitleExists() {
        Existing existing = existing("登录", 10);

        Plan plan = plan(DuplicateMode.CREATE, fields(), List.of(row(1, "登录"), row(2, "  登录  "),
                row(3, "注册")), List.of(existing));

        assertThat(plan.rows()).extracting(PlannedRow::outcome)
                .containsExactly(Outcome.CREATE, Outcome.CREATE, Outcome.CREATE);
        assertThat(plan.rows()).extracting(PlannedRow::title).containsExactly("登录", "登录", "注册");
        assertThat(plan.rows()).allSatisfy(row -> assertThat(row.issues()).isEmpty());
    }

    @Test
    void skipModeSkipsRowsWhoseTitleExistsAndCreatesTheRest() {
        Existing existing = existing("登录", 10);

        Plan plan = plan(DuplicateMode.SKIP, fields(), List.of(row(1, " 登录"), row(2, "注册")),
                List.of(existing));

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.SKIP);
        assertThat(plan.rows().get(0).target()).isEqualTo(existing);
        assertThat(codes(plan.rows().get(0))).containsExactly("DUPLICATE_EXISTS");
        assertThat(plan.rows().get(1).outcome()).isEqualTo(Outcome.CREATE);
        assertThat(plan.count(Outcome.SKIP)).isEqualTo(1);
    }

    @Test
    void skipModeWithSeveralMatchesStillSkipsButNamesNoSingleTarget() {
        Plan plan = plan(DuplicateMode.SKIP, fields(), List.of(row(1, "登录")),
                List.of(existing("登录", 10), existing("登录", 11)));

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.SKIP);
        assertThat(plan.rows().get(0).target()).isNull();
    }

    @Test
    void updateModeUpdatesTheSingleMatchAndCreatesWhenNothingMatches() {
        Existing existing = existing("登录", 10);

        Plan plan = plan(DuplicateMode.UPDATE, fields(), List.of(row(1, "登录"), row(2, "注册")),
                List.of(existing));

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.UPDATE);
        assertThat(plan.rows().get(0).target()).isEqualTo(existing);
        assertThat(plan.rows().get(1).outcome()).isEqualTo(Outcome.CREATE);
    }

    @Test
    void updateModeRejectsSeveralMatchesAsAmbiguous() {
        Plan plan = plan(DuplicateMode.UPDATE, fields(), List.of(row(1, "登录")),
                List.of(existing("登录", 10), existing("登录", 11)));

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(plan.rows().get(0).issues()).containsExactly(new Issue("MATCH_AMBIGUOUS", "2"));
    }

    @Test
    void updateModeFlagsEveryRowOfATitleRepeatedInTheFileThatMatchesAnExistingItem() {
        Plan plan = plan(DuplicateMode.UPDATE, fields(), List.of(row(1, "开发进度跟进"),
                row(2, "其他"), row(3, " 开发进度跟进")), List.of(existing("开发进度跟进", 10)));

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(codes(plan.rows().get(0))).containsExactly("DUPLICATE_IN_FILE");
        assertThat(plan.rows().get(1).outcome()).isEqualTo(Outcome.CREATE);
        assertThat(plan.rows().get(2).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(codes(plan.rows().get(2))).containsExactly("DUPLICATE_IN_FILE");
    }

    @Test
    void repeatedTitlesWithoutAnyExistingMatchAreAllCreated() {
        List<Row> rows = List.of(row(1, "开发进度跟进"), row(2, "开发进度跟进"));

        for (DuplicateMode mode : DuplicateMode.values()) {
            Plan plan = plan(mode, fields(), rows, List.of());
            assertThat(plan.rows()).extracting(PlannedRow::outcome)
                    .containsExactly(Outcome.CREATE, Outcome.CREATE);
        }
    }

    @Test
    void titlesAreTrimmedAndLimitedToOneToThreeHundredCharacters() {
        Plan plan = plan(DuplicateMode.CREATE, fields(), List.of(row(1, null), row(2, "   "),
                row(3, "x".repeat(301)), row(4, "  " + "x".repeat(300) + "  "), row(5, "x")),
                List.of());

        assertThat(plan.rows()).extracting(PlannedRow::outcome).containsExactly(Outcome.ERROR,
                Outcome.ERROR, Outcome.ERROR, Outcome.CREATE, Outcome.CREATE);
        assertThat(plan.rows().subList(0, 3)).allSatisfy(row ->
                assertThat(codes(row)).containsExactly("TITLE_INVALID"));
    }

    @Test
    void invalidTitleIsNeverMatchedAgainstExistingItems() {
        Plan plan = plan(DuplicateMode.UPDATE, fields(), List.of(row(1, "   ")),
                List.of(existing("", 10)));

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(codes(plan.rows().get(0))).containsExactly("TITLE_INVALID");
    }

    @Test
    void newItemsMustCarryValidCategoryStatusPriorityAndAssignees() {
        Row invalid = new Row(1, "登录", id(99), "OLD", "URGENT", List.of(id(98)), null, null, null);

        Plan plan = plan(DuplicateMode.CREATE, fields(), List.of(invalid), List.of());

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(codes(plan.rows().get(0))).containsExactly("CONTENT_INVALID", "STATUS_INVALID",
                "PRIORITY_INVALID", "ASSIGNEE_INVALID");
    }

    @Test
    void blankPriorityIsEmptyNotInvalid() {
        Row blank = new Row(1, "登录", CONTENT, "NOT_STARTED", "  ", List.of(), null, null, null);

        Plan plan = plan(DuplicateMode.CREATE, fields(), List.of(blank), List.of());

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.CREATE);
        assertThat(WorkItemImportPlanner.normalizePriority("  ")).isNull();
        assertThat(WorkItemImportPlanner.normalizePriority(" HIGH ")).isEqualTo("HIGH");
    }

    @Test
    void updateOnlyValidatesMappedFieldsAndAcceptsValuesTheItemAlreadyHas() {
        Existing existing = new Existing(id(10), CONTENT, "BCI-1", "登录", "LEGACY", "OLD",
                List.of(id(97)));
        Row row = new Row(1, "登录", id(99), "LEGACY", "OLD", List.of(id(97)), null, null, null);

        Plan unmapped = plan(DuplicateMode.UPDATE, fields(), List.of(row), List.of(existing));
        Plan sameValues = plan(DuplicateMode.UPDATE, fields(Field.STATUS, Field.PRIORITY,
                Field.ASSIGNEES), List.of(row), List.of(existing));
        Plan badContent = plan(DuplicateMode.UPDATE, fields(Field.CONTENT), List.of(row),
                List.of(existing));

        assertThat(unmapped.rows().get(0).outcome()).isEqualTo(Outcome.UPDATE);
        assertThat(sameValues.rows().get(0).outcome()).isEqualTo(Outcome.UPDATE);
        assertThat(badContent.rows().get(0).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(codes(badContent.rows().get(0))).containsExactly("CONTENT_INVALID");
    }

    @Test
    void updateAcceptsEmptyValuesForMappedFieldsSoTheyCanBeCleared() {
        Existing existing = new Existing(id(10), CONTENT, "BCI-1", "登录", "NOT_STARTED", "HIGH",
                List.of(MEMBER));
        Row empty = new Row(1, "登录", CONTENT, "NOT_STARTED", null, List.of(),
                (LocalDate) null, null, null);

        Plan plan = plan(DuplicateMode.UPDATE, fields(Field.PRIORITY, Field.ASSIGNEES,
                Field.DUE_DATE, Field.NOTES, Field.PARENT), List.of(empty), List.of(existing));

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.UPDATE);
        assertThat(plan.rows().get(0).issues()).isEmpty();
        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.CLEAR);
    }

    @Test
    void rowsWithErrorsDoNotCountAsSkippedOrCreated() {
        Plan plan = plan(DuplicateMode.CREATE, fields(), List.of(row(1, ""), row(2, "登录")),
                List.of());

        assertThat(plan.count(Outcome.ERROR)).isEqualTo(1);
        assertThat(plan.count(Outcome.CREATE)).isEqualTo(1);
    }

    @Test
    void parentIsFoundAmongTheNewRowsOfTheFile() {
        Plan plan = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "子项", "父项"), row(2, "父项")), List.of());

        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.SET);
        assertThat(plan.rows().get(0).parent()).isEqualTo(Ref.row(1));
        assertThat(plan.rows().get(0).issues()).isEmpty();
        assertThat(plan.rows().get(1).parentMode()).isEqualTo(ParentMode.KEEP);
    }

    @Test
    void parentInTheFileWinsOverAnExistingItemWithTheSameTitle() {
        Plan plan = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "子项", "父项"), row(2, "父项")), List.of(existing("父项", 10)));

        assertThat(plan.rows().get(0).parent()).isEqualTo(Ref.row(1));
    }

    @Test
    void parentFallsBackToAnExistingItem() {
        Existing parent = existing("父项", 10);

        Plan plan = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "子项", " 父项 ")), List.of(parent));

        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.SET);
        assertThat(plan.rows().get(0).parent()).isEqualTo(Ref.existing(parent.id()));
    }

    @Test
    void parentOfASkippedRowIsTheExistingItemItMatched() {
        Existing parent = existing("父项", 10);

        Plan plan = plan(DuplicateMode.SKIP, fields(Field.PARENT), List.of(row(1, "父项"),
                parented(2, "子项", "父项")), List.of(parent));

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.SKIP);
        assertThat(plan.rows().get(1).parent()).isEqualTo(Ref.existing(parent.id()));
    }

    @Test
    void parentThatIsMissingIsReportedAndTheRowStaysTopLevel() {
        Plan plan = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "子项", "不存在")), List.of());

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.CREATE);
        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.KEEP);
        assertThat(plan.rows().get(0).issues()).containsExactly(new Issue("PARENT_NOT_FOUND", "不存在"));
    }

    @Test
    void parentRowThatFailedIsNotAnAcceptableParent() {
        Row broken = new Row(2, "父项", CONTENT, "OLD", null, List.of(), null, null, null);

        Plan plan = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "子项", "父项"), broken), List.of());

        assertThat(plan.rows().get(1).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(codes(plan.rows().get(0))).containsExactly("PARENT_NOT_FOUND");
    }

    @Test
    void severalParentCandidatesAreAmbiguousInTheFileAndInTheProject() {
        Plan inFile = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "子项", "父项"), row(2, "父项"), row(3, "父项")), List.of());
        Plan inProject = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "子项", "父项")), List.of(existing("父项", 10), existing("父项", 11)));

        assertThat(codes(inFile.rows().get(0))).containsExactly("PARENT_AMBIGUOUS");
        assertThat(inFile.rows().get(0).parentMode()).isEqualTo(ParentMode.KEEP);
        assertThat(codes(inProject.rows().get(0))).containsExactly("PARENT_AMBIGUOUS");
    }

    @Test
    void parentThatIsItselfAChildInTheFileIsFlattenedToTheRoot() {
        Plan plan = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "孙项", "子项"), parented(2, "子项", "根"), row(3, "根")), List.of());

        assertThat(plan.rows().get(0).parent()).isEqualTo(Ref.row(2));
        assertThat(codes(plan.rows().get(0))).containsExactly("PARENT_FLATTENED");
        assertThat(plan.rows().get(1).parent()).isEqualTo(Ref.row(2));
        assertThat(plan.rows().get(1).issues()).isEmpty();
    }

    @Test
    void parentThatIsAnExistingChildIsFlattenedToItsParent() {
        Existing root = existing("根", 10);
        Existing child = existing("子项", 11);

        Plan plan = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "孙项", "子项")), List.of(root, child),
                List.of(new ParentLink(root.id(), child.id())));

        assertThat(plan.rows().get(0).parent()).isEqualTo(Ref.existing(root.id()));
        assertThat(codes(plan.rows().get(0))).containsExactly("PARENT_FLATTENED");
    }

    @Test
    void parentThatBecomesAChildInTheSameUpdateIsFlattenedToo() {
        Existing parent = existing("父项", 10);
        Existing root = existing("根", 11);

        Plan plan = plan(DuplicateMode.UPDATE, fields(Field.PARENT), List.of(
                parented(1, "父项", "根"), parented(2, "新项", "父项")), List.of(parent, root));

        assertThat(plan.rows().get(0).parent()).isEqualTo(Ref.existing(root.id()));
        assertThat(plan.rows().get(1).parent()).isEqualTo(Ref.existing(root.id()));
        assertThat(codes(plan.rows().get(1))).containsExactly("PARENT_FLATTENED");
    }

    @Test
    void selfReferenceStaysTopLevel() {
        Plan plan = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "任务", "任务")), List.of());

        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.KEEP);
        assertThat(codes(plan.rows().get(0))).containsExactly("PARENT_CYCLE");
    }

    @Test
    void everyRowOfACycleStaysTopLevelAndRowsPointingIntoItAreUnaffected() {
        Plan plan = plan(DuplicateMode.CREATE, fields(Field.PARENT), List.of(
                parented(1, "甲", "乙"), parented(2, "乙", "丙"), parented(3, "丙", "甲"),
                parented(4, "丁", "甲")), List.of());

        for (int index = 0; index < 3; index++) {
            assertThat(plan.rows().get(index).parentMode()).isEqualTo(ParentMode.KEEP);
            assertThat(codes(plan.rows().get(index))).containsExactly("PARENT_CYCLE");
        }
        assertThat(plan.rows().get(3).parentMode()).isEqualTo(ParentMode.SET);
        assertThat(plan.rows().get(3).parent()).isEqualTo(Ref.row(0));
        assertThat(plan.rows().get(3).issues()).isEmpty();
    }

    @Test
    void existingItemWithChildrenKeepsItsPlaceWhenTheFileMakesItAChild() {
        Existing parent = existing("父项", 10);
        Existing child = existing("子项", 11);
        Existing other = existing("别的", 12);

        Plan plan = plan(DuplicateMode.UPDATE, fields(Field.PARENT), List.of(
                parented(1, "父项", "别的")), List.of(parent, child, other),
                List.of(new ParentLink(parent.id(), child.id())));

        assertThat(plan.rows().get(0).outcome()).isEqualTo(Outcome.UPDATE);
        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.KEEP);
        assertThat(codes(plan.rows().get(0))).containsExactly("PARENT_HAS_CHILDREN");
    }

    @Test
    void updateWithoutAParentTitleDetachesTheExistingParent() {
        Existing parent = existing("父项", 10);
        Existing child = existing("子项", 11);

        Plan plan = plan(DuplicateMode.UPDATE, fields(Field.PARENT), List.of(row(1, "子项")),
                List.of(parent, child), List.of(new ParentLink(parent.id(), child.id())));

        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.CLEAR);
        assertThat(plan.rows().get(0).issues()).isEmpty();
    }

    @Test
    void updateKeepsTheExistingParentWhenTheParentColumnIsNotMapped() {
        Existing parent = existing("父项", 10);
        Existing child = existing("子项", 11);

        Plan plan = plan(DuplicateMode.UPDATE, fields(), List.of(parented(1, "子项", "别人")),
                List.of(parent, child), List.of(new ParentLink(parent.id(), child.id())));

        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.KEEP);
        assertThat(plan.rows().get(0).issues()).isEmpty();
    }

    @Test
    void updateThatCannotFindItsParentKeepsTheExistingParentAndSaysSo() {
        Existing parent = existing("父项", 10);
        Existing child = existing("子项", 11);

        Plan plan = plan(DuplicateMode.UPDATE, fields(Field.PARENT), List.of(
                parented(1, "子项", "没有")), List.of(parent, child),
                List.of(new ParentLink(parent.id(), child.id())));

        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.KEEP);
        assertThat(codes(plan.rows().get(0))).containsExactly("PARENT_NOT_FOUND");
    }

    @Test
    void updateWithAnAmbiguousParentKeepsTheExistingParent() {
        Existing parent = existing("父项", 10);
        Existing child = existing("子项", 11);

        Plan plan = plan(DuplicateMode.UPDATE, fields(Field.PARENT), List.of(
                parented(1, "子项", "别人")), List.of(parent, child, existing("别人", 12),
                existing("别人", 13)), List.of(new ParentLink(parent.id(), child.id())));

        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.KEEP);
        assertThat(codes(plan.rows().get(0))).containsExactly("PARENT_AMBIGUOUS");
    }

    @Test
    void updateInACycleKeepsTheExistingParents() {
        Existing a = existing("甲", 10);
        Existing b = existing("乙", 11);
        Existing root = existing("根", 12);

        Plan plan = plan(DuplicateMode.UPDATE, fields(Field.PARENT), List.of(
                parented(1, "甲", "乙"), parented(2, "乙", "甲")), List.of(a, b, root),
                List.of(new ParentLink(root.id(), a.id())));

        assertThat(plan.rows()).allSatisfy(row -> {
            assertThat(row.parentMode()).isEqualTo(ParentMode.KEEP);
            assertThat(codes(row)).containsExactly("PARENT_CYCLE");
        });
    }

    @Test
    void parentColumnIsIgnoredWhenNotMapped() {
        Plan plan = plan(DuplicateMode.CREATE, fields(), List.of(parented(1, "子项", "父项"),
                row(2, "父项")), List.of());

        assertThat(plan.rows().get(0).parentMode()).isEqualTo(ParentMode.KEEP);
        assertThat(plan.rows().get(0).issues()).isEmpty();
    }

    private static Plan plan(DuplicateMode mode, Set<Field> fields, List<Row> rows,
            List<Existing> existing) {
        return plan(mode, fields, rows, existing, List.of());
    }

    private static Plan plan(DuplicateMode mode, Set<Field> fields, List<Row> rows,
            List<Existing> existing, List<ParentLink> links) {
        return WorkItemImportPlanner.plan(new Input(mode, fields, rows, existing, links, CATALOG));
    }

    private static Set<Field> fields(Field... fields) {
        Set<Field> set = EnumSet.noneOf(Field.class);
        set.addAll(List.of(fields));
        return set;
    }

    private static Row row(int number, String title) {
        return new Row(number, title, CONTENT, "NOT_STARTED", null, List.of(), null, null, null);
    }

    private static Row parented(int number, String title, String parentTitle) {
        return new Row(number, title, CONTENT, "NOT_STARTED", null, List.of(), null, parentTitle, null);
    }

    private static Existing existing(String title, int number) {
        return new Existing(id(1000 + number), CONTENT, "BCI-" + number, title, "NOT_STARTED", null,
                List.of());
    }

    private static List<String> codes(PlannedRow row) {
        return row.issues().stream().map(Issue::code).toList();
    }

    private static UUID id(int number) {
        return new UUID(0x4000L, 0x8000000000000000L | number);
    }
}
