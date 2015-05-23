package net.iponweb.disthene.service.stats;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import net.iponweb.disthene.bean.Metric;
import net.iponweb.disthene.config.Rollup;
import net.iponweb.disthene.config.StatsConfiguration;
import net.iponweb.disthene.service.events.MetricReceivedEvent;
import net.iponweb.disthene.service.events.MetricStoreEvent;
import net.iponweb.disthene.service.events.StoreErrorEvent;
import net.iponweb.disthene.service.events.StoreSuccessEvent;
import net.iponweb.disthene.service.general.GeneralStore;
import net.iponweb.disthene.service.util.NameThreadFactory;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Andrei Ivanov
 */
public class Stats {
    private static final String SCHEDULER_NAME = "distheneStatsFlusher";

    private Logger logger = Logger.getLogger(Stats.class);

    private StatsConfiguration statsConfiguration;

    private EventBus bus;
    private Rollup rollup;
    private AtomicLong storeSuccess = new AtomicLong(0);
    private AtomicLong storeError = new AtomicLong(0);
    private final Map<String, StatsRecord> stats = new HashMap<>();
    private ScheduledExecutorService rollupAggregatorScheduler = Executors.newScheduledThreadPool(1);

    public Stats(EventBus bus, StatsConfiguration statsConfiguration, Rollup rollup) {
        this.statsConfiguration = statsConfiguration;
        this.bus = bus;
        this.rollup = rollup;
        bus.register(this);

        ScheduledExecutorService rollupAggregatorScheduler = Executors.newScheduledThreadPool(1, new NameThreadFactory(SCHEDULER_NAME));
        rollupAggregatorScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        }, statsConfiguration.getInterval(), statsConfiguration.getInterval(), TimeUnit.SECONDS);

    }

    @Subscribe
    @AllowConcurrentEvents
    public void handle(MetricReceivedEvent metricReceivedEvent) {
        synchronized (stats) {
            StatsRecord statsRecord = stats.get(metricReceivedEvent.getMetric().getTenant());
            if (statsRecord == null) {
                statsRecord = new StatsRecord();
                stats.put(metricReceivedEvent.getMetric().getTenant(), statsRecord);
            }

            statsRecord.incMetricsReceived();
        }
    }

    @Subscribe
    @AllowConcurrentEvents
    public void handle(MetricStoreEvent metricStoreEvent) {
        synchronized (stats) {
            StatsRecord statsRecord = stats.get(metricStoreEvent.getMetric().getTenant());
            if (statsRecord == null) {
                statsRecord = new StatsRecord();
                stats.put(metricStoreEvent.getMetric().getTenant(), statsRecord);
            }

            statsRecord.incMetricsWritten();
        }
    }

    @Subscribe
    @AllowConcurrentEvents
    public void handle(StoreSuccessEvent storeSuccessEvent) {
        storeSuccess.addAndGet(storeSuccessEvent.getCount());
    }

    @Subscribe
    @AllowConcurrentEvents
    public void handle(StoreErrorEvent storeErrorEvent) {
        storeError.addAndGet(storeErrorEvent.getCount());
    }

    public void flush() {
        Map<String, StatsRecord> statsToFlush = new HashMap<>();
        long storeSuccess;
        long storeError;

        synchronized (stats) {
            for(Map.Entry<String, StatsRecord> entry : stats.entrySet()) {
                statsToFlush.put(entry.getKey(), new StatsRecord(entry.getValue()));
                entry.getValue().reset();
            }

            storeSuccess = this.storeSuccess.getAndSet(0);
            storeError = this.storeError.getAndSet(0);
        }

        doFlush(statsToFlush, storeSuccess, storeError, DateTime.now(DateTimeZone.UTC).withSecondOfMinute(0));
    }

    private synchronized void doFlush(Map<String, StatsRecord> stats, long storeSuccess, long storeError, DateTime dt) {
        logger.debug("Flushing stats for " + dt);

        long totalReceived = 0;
        long totalWritten = 0;

        if (statsConfiguration.isLog()) {
            logger.info("Disthene stats:");
            logger.info("======================================================================================");
            logger.info("\tTenant\tmetrics_received\twrite_count");
            logger.info("======================================================================================");
        }

        for(Map.Entry<String, StatsRecord> entry : stats.entrySet()) {
            String tenant = entry.getKey();
            StatsRecord statsRecord = entry.getValue();

            totalReceived += statsRecord.getMetricsReceived();
            totalWritten += statsRecord.getMetricsWritten();

            bus.post(new MetricStoreEvent(new Metric(
                    statsConfiguration.getTenant(),
                    statsConfiguration.getHostname() + ".disthene.tenants." + tenant + ".metrics_received",
                    rollup.getRollup(),
                    rollup.getPeriod(),
                    statsRecord.getMetricsReceived(),
                    dt
            )));

            bus.post(new MetricStoreEvent(new Metric(
                    statsConfiguration.getTenant(),
                    statsConfiguration.getHostname() + ".disthene.tenants." + tenant + ".write_count",
                    rollup.getRollup(),
                    rollup.getPeriod(),
                    statsRecord.getMetricsWritten(),
                    dt
            )));

            if (statsConfiguration.isLog()) {
                logger.info("\t" + tenant + "\t" + statsRecord.metricsReceived + "\t" + statsRecord.getMetricsWritten());
            }
        }

/*
        generalStore.store(new Metric(
                statsConfiguration.getTenant(),
                statsConfiguration.getHostname() + ".disthene.metrics_received",
                baseRollup.getRollup(),
                baseRollup.getPeriod(),
                totalReceived,
                dt
        ));

        generalStore.store(new Metric(
                statsConfiguration.getTenant(),
                statsConfiguration.getHostname() + ".disthene.write_count",
                baseRollup.getRollup(),
                baseRollup.getPeriod(),
                totalWritten,
                dt
        ));

        generalStore.store(new Metric(
                statsConfiguration.getTenant(),
                statsConfiguration.getHostname() + ".disthene.store.success",
                baseRollup.getRollup(),
                baseRollup.getPeriod(),
                storeSuccess,
                dt
        ));

        generalStore.store(new Metric(
                statsConfiguration.getTenant(),
                statsConfiguration.getHostname() + ".disthene.store.error",
                baseRollup.getRollup(),
                baseRollup.getPeriod(),
                storeError,
                dt
        ));
*/

        if (statsConfiguration.isLog()) {
            logger.info("\t" + "total" + "\t" + totalReceived + "\t" + totalWritten);
            logger.info("======================================================================================");
            logger.info("\tstore.success:\t" + storeSuccess);
            logger.info("\tstore.error:\t" + storeError);
            logger.info("======================================================================================");
        }
    }

    private class StatsRecord {
        private long metricsReceived = 0;
        private long metricsWritten = 0;

        private StatsRecord() {
        }

        private StatsRecord(StatsRecord statsRecord) {
            metricsReceived = statsRecord.metricsReceived;
            metricsWritten = statsRecord.metricsWritten;
        }

        public void reset() {
            metricsReceived = 0;
            metricsWritten = 0;
        }

        public void incMetricsReceived() {
            metricsReceived++;
        }

        public void incMetricsWritten() {
            metricsWritten++;
        }

        public long getMetricsReceived() {
            return metricsReceived;
        }

        public long getMetricsWritten() {
            return metricsWritten;
        }
    }
}