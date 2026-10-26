package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import java.util.UUID;

/** What a time session is recorded against: a work item, or a fixed category optionally linked to a project. */
public sealed interface TimeSubject extends Comparable<TimeSubject> {
    record Item(UUID workItemId) implements TimeSubject {
        public String key() { return "item:"+workItemId; }
    }
    record Category(TimeCategory category, UUID projectId) implements TimeSubject {
        public String key() { return "category:"+category.name()+(projectId==null ? "" : ":"+projectId); }
    }

    String key();

    default UUID workItemId() { return this instanceof Item item ? item.workItemId() : null; }
    default TimeCategory timeCategory() { return this instanceof Category category ? category.category() : null; }

    default UUID categoryProjectId() { return this instanceof Category category ? category.projectId() : null; }

    @Override default int compareTo(TimeSubject other) { return key().compareTo(other.key()); }

    static TimeSubject of(UUID workItemId, TimeCategory category) { return of(workItemId,category,null); }
    static TimeSubject of(UUID workItemId, TimeCategory category, UUID categoryProjectId) {
        if((workItemId==null)==(category==null))
            throw ApplicationException.validation(new FieldViolation("workItemId","REQUIRED","请选择工作项或分类"));
        return workItemId!=null ? new Item(workItemId) : new Category(category,categoryProjectId);
    }
}
