package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.purge.ProjectDataPurger;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public final class ProjectPurgeStages {
    private static final List<String> STAGES=List.of("NOTIFICATION","AUDIT","FILESTORAGE","OUTBOX","WORKITEM","REPORTING","CATALOG");
    private final Map<String,ProjectDataPurger> providers;
    public ProjectPurgeStages(List<ProjectDataPurger> purgers) {
        Map<String,ProjectDataPurger> indexed=new HashMap<>();
        for(var purger:purgers) {
            int position=STAGES.indexOf(purger.stage());
            if(position<0 || purger.order()!=(position+1)*10 || indexed.put(purger.stage(),purger)!=null)
                throw new IllegalStateException("project purge provider declaration mismatch");
        }
        if(!indexed.keySet().equals(java.util.Set.copyOf(STAGES)))
            throw new IllegalStateException("project purge provider coverage mismatch");
        providers=Map.copyOf(indexed);
    }
    public ProjectDataPurger provider(String stage) {
        var provider=providers.get(stage);
        if(provider==null) throw new IllegalStateException("unknown project purge stage");
        return provider;
    }
    public String next(String stage) {
        int index=STAGES.indexOf(stage);
        if(index<0 || index==STAGES.size()-1) throw new IllegalStateException("project purge stage cannot advance");
        return STAGES.get(index+1);
    }
}
