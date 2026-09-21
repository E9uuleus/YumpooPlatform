package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.domain.StatisticsFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkItemChartRepository {
    List<UUID> matchingIds(UUID company, ChartStatistics.TableScope scope, List<UUID> ids);
    java.util.Map<UUID, Long> childCounts(UUID company, ChartStatistics.TableScope scope, List<UUID> ids);
    List<ChartStatistics.Point> aggregate(UUID companyId, StatisticsFilter global, StatisticsFilter local,
            ChartStatistics.Request chart, Instant asOf);
    List<WorkItemStatisticsRepository.Item> items(UUID companyId, StatisticsFilter global, StatisticsFilter local,
            ChartStatistics.Request chart, ChartStatistics.Selection selection, Instant asOf, int offset, int limit);
    long count(UUID companyId, StatisticsFilter global, StatisticsFilter local,
            ChartStatistics.Request chart, ChartStatistics.Selection selection, Instant asOf);
}
