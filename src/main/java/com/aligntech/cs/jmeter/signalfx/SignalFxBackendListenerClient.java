/*
 * Copyright 1998-2017 The Apache Software Foundation
 * Copyright 2017 Align Technology, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aligntech.cs.jmeter.signalfx;

import com.google.common.collect.ImmutableMap;
import com.signalfx.endpoint.SignalFxEndpoint;
import com.signalfx.metrics.connection.HttpDataPointProtobufReceiverFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jmeter.visualizers.backend.SamplerMetric;
import org.apache.jmeter.visualizers.backend.UserMetric;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Backend listener implementation that records and sends data to SignalFX service.
 *
 * Note: BackendListener interface itself is too abstract to create code devoted solely for working with SFX, that's
 * why boilerplate code in this class related to creating list of properties and iterating over metrics is taken from
 * GraphiteBackendListener implementation and adapted to SFX needs with slight refactoring.
 */
public class SignalFxBackendListenerClient extends AbstractBackendListenerClient implements Runnable {

    private static final String SIGNAL_FX_DEFAULT_ENDPOINT = SignalFxEndpoint.DEFAULT_SCHEME + "://" + SignalFxEndpoint.DEFAULT_HOSTNAME + ":" + SignalFxEndpoint.DEFAULT_PORT;
    private static final Logger log = LoggingManager.getLoggerForClass();

    private static final String SEPARATOR = ";";

    private static final String CUMULATED_METRICS = "_all_";

    private static final String METRIC_OK_COUNT = "ok.count";
    private static final String METRIC_OK_MIN_RESPONSE_TIME = "ok.min";
    private static final String METRIC_OK_MAX_RESPONSE_TIME = "ok.max";
    private static final String METRIC_OK_AVG_RESPONSE_TIME = "ok.avg";

    private static final String METRIC_KO_COUNT = "ko.count";
    private static final String METRIC_KO_MIN_RESPONSE_TIME = "ko.min";
    private static final String METRIC_KO_MAX_RESPONSE_TIME = "ko.max";
    private static final String METRIC_KO_AVG_RESPONSE_TIME = "ko.avg";

    private static final String METRIC_ALL_COUNT = "a.count";
    private static final String METRIC_ALL_MIN_RESPONSE_TIME = "a.min";
    private static final String METRIC_ALL_MAX_RESPONSE_TIME = "a.max";
    private static final String METRIC_ALL_AVG_RESPONSE_TIME = "a.avg";

    private static final String METRIC_ALL_HITS_COUNT = "h.count";

    // User Metrics
    private static final String METRIC_MAX_ACTIVE_THREADS = "maxAT";
    private static final String METRIC_MIN_ACTIVE_THREADS = "minAT";
    private static final String METRIC_MEAN_ACTIVE_THREADS = "meanAT";
    private static final String METRIC_STARTED_THREADS = "startedT";
    private static final String METRIC_FINISHED_THREADS = "endedT";

    private static final String SIGNAL_FX_ENDPOINT = "signalFxEndpoint";
    private static final String AUTH_TOKEN = "authToken";
    private static final String DIMENSIONS = "dimensions";
    private static final String SAMPLERS_LIST = "samplersList";
    private static final String USE_REGEXP_FOR_SAMPLERS_LIST = "useRegexpForSamplersList";
    private static final String PERCENTILES = "percentiles";
    private static final String SIGNALFX_TIMEOUT = "signalFxTimeoutMs";

    private static final String TEST_TITLE = "testTitle";
    private static final String TEST_TITLE_DEFAULT = "Test name";

    private static final String EVENT_TAGS = "eventTags";
    private static final String SUMMARY_ONLY = "summaryOnly";

    private static final long METRIC_SEND_INTERVAL = 5L;
    private static final String PERCENTILES_DEFAULT = "90;95;99";

    private static final String ALL_CONTEXT_NAME = "all";
    private static final String TEST_CONTEXT_NAME = "test";

    private Map<String, Float> okPercentiles;
    private Map<String, Float> koPercentiles;
    private Map<String, Float> allPercentiles;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture timerHandle;

    private boolean summaryOnly;
    private String testTitle;
    private SamplersFilter filter;

    private SignalFxMetricsSender metricsSender;

    private Lock lock = new ReentrantLock();

    @Override
    public void run() {
        sendMetrics();
    }

    private void sendMetrics() {
        MetricCollector metricCollector = metricsSender.startCollection();
        try {
            lock.lock();
            for (Map.Entry<String, SamplerMetric> entry : getMetricsPerSampler().entrySet()) {
                final String key = entry.getKey();
                final SamplerMetric metric = entry.getValue();
                if (key.equals(CUMULATED_METRICS)) {
                    addMetrics(metricCollector, ALL_CONTEXT_NAME, metric);
                } else {
                    addMetrics(metricCollector, key, metric);
                }
                // We are computing on interval basis so cleanup
                metric.resetForTimeInterval();
            }
        } finally {
            lock.unlock();
        }
        UserMetric userMetric = getUserMetrics();
        metricCollector.addGauge(TEST_CONTEXT_NAME, METRIC_MIN_ACTIVE_THREADS, userMetric.getMinActiveThreads());
        metricCollector.addGauge(TEST_CONTEXT_NAME, METRIC_MAX_ACTIVE_THREADS, userMetric.getMaxActiveThreads());
        metricCollector.addGauge(TEST_CONTEXT_NAME, METRIC_MEAN_ACTIVE_THREADS, userMetric.getMeanActiveThreads());
        metricCollector.addGauge(TEST_CONTEXT_NAME, METRIC_STARTED_THREADS, userMetric.getStartedThreads());
        metricCollector.addGauge(TEST_CONTEXT_NAME, METRIC_FINISHED_THREADS, userMetric.getFinishedThreads());

        metricsSender.writeAndSendMetrics(metricCollector);
    }

    private void addMetrics(MetricCollector collector, String contextName, SamplerMetric metric) {
        if (metric.getTotal() > 0) {
            collector.addCounter(contextName, METRIC_OK_COUNT, metric.getSuccesses());
            collector.addCounter(contextName, METRIC_KO_COUNT, metric.getFailures());
            collector.addCounter(contextName, METRIC_ALL_COUNT, metric.getTotal());
            collector.addCounter(contextName, METRIC_ALL_HITS_COUNT, metric.getHits());
            if (metric.getSuccesses() > 0) {
                collector.addGauge(contextName, METRIC_OK_MIN_RESPONSE_TIME, metric.getOkMinTime());
                collector.addGauge(contextName, METRIC_OK_MAX_RESPONSE_TIME, metric.getOkMaxTime());
                collector.addGauge(contextName, METRIC_OK_AVG_RESPONSE_TIME, metric.getOkMean());
                for (Map.Entry<String, Float> entry : okPercentiles.entrySet()) {
                    collector.addGauge(contextName, entry.getKey(),
                            metric.getOkPercentile(entry.getValue().doubleValue()));
                }
            }
            if (metric.getFailures() > 0) {
                collector.addGauge(contextName, METRIC_KO_MIN_RESPONSE_TIME, metric.getKoMinTime());
                collector.addGauge(contextName, METRIC_KO_MAX_RESPONSE_TIME, metric.getKoMaxTime());
                collector.addGauge(contextName, METRIC_KO_AVG_RESPONSE_TIME, metric.getKoMean());
                for (Map.Entry<String, Float> entry : koPercentiles.entrySet()) {
                    collector.addGauge(contextName, entry.getKey(),
                            metric.getKoPercentile(entry.getValue().doubleValue()));
                }
            }
            collector.addGauge(contextName, METRIC_ALL_MIN_RESPONSE_TIME, metric.getAllMinTime());
            collector.addGauge(contextName, METRIC_ALL_MAX_RESPONSE_TIME, metric.getAllMaxTime());
            collector.addGauge(contextName, METRIC_ALL_AVG_RESPONSE_TIME, metric.getAllMean());
            for (Map.Entry<String, Float> entry : allPercentiles.entrySet()) {
                collector.addGauge(contextName, entry.getKey(),
                        metric.getAllPercentile(entry.getValue().doubleValue()));
            }
        }
    }

    @Override
    public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext backendListenerContext) {
        try {
            lock.lock();
            UserMetric userMetrics = getUserMetrics();
            for (SampleResult sampleResult : sampleResults) {
                userMetrics.add(sampleResult);

                if (!summaryOnly) {
                    if (filter.metricReported(sampleResult.getSampleLabel())) {
                        SamplerMetric samplerMetric = getSamplerMetric(sampleResult.getSampleLabel());
                        samplerMetric.add(sampleResult);
                    }
                }
                SamplerMetric cumulatedMetrics = getSamplerMetric(CUMULATED_METRICS);
                cumulatedMetrics.add(sampleResult);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        super.setupTest(context);
        initDefaults();

        summaryOnly = context.getBooleanParameter(SUMMARY_ONLY, false);
        filter = createFilter(context);
        testTitle = context.getParameter(TEST_TITLE, TEST_TITLE_DEFAULT);
        setupPercentiles(extractPercentiles(context.getParameter(PERCENTILES, "")));

        metricsSender = new SignalFxMetricsSenderImpl(
                context.getParameter(SIGNAL_FX_ENDPOINT, SIGNAL_FX_DEFAULT_ENDPOINT),
                context.getParameter(AUTH_TOKEN),
                parseDimensions(context.getParameter(DIMENSIONS, "")),
                parseDimensions(context.getParameter(EVENT_TAGS, "")),
                context.getIntParameter(SIGNALFX_TIMEOUT, HttpDataPointProtobufReceiverFactory.DEFAULT_TIMEOUT_MS)
        );

        reportTestsStart();

        this.scheduler = Executors.newScheduledThreadPool(1);
        this.timerHandle = this.scheduler.scheduleAtFixedRate(this, 1L, METRIC_SEND_INTERVAL, TimeUnit.SECONDS);
    }

    private void initDefaults() {
        this.okPercentiles = new HashMap<>();
        this.allPercentiles = new HashMap<>();
        this.koPercentiles = new HashMap<>();
    }

    private SamplersFilter createFilter(BackendListenerContext context) {
        String samplersList = context.getParameter(SAMPLERS_LIST, "");
        boolean useRegexForSamplers = context.getBooleanParameter(USE_REGEXP_FOR_SAMPLERS_LIST, false);
        return SamplersFilter.create(samplersList, useRegexForSamplers);
    }

    private Collection<String> extractPercentiles(String userValue) {
        String[] split = userValue.split(SEPARATOR);
        ArrayList<String> percentiles = new ArrayList<>();
        for (String value : split) {
            String trimmedValue = value.trim();
            if (!StringUtils.isEmpty(trimmedValue)) {
                percentiles.add(trimmedValue);
            }
        }
        return percentiles;
    }

    private void setupPercentiles(Collection<String> percentiles) {
        DecimalFormat format = new DecimalFormat("0.##");
        for (String percentile : percentiles) {
            try {
                Float percentileValue = Float.valueOf(percentile.trim());
                String formattedPercentile = format.format(percentileValue);
                okPercentiles.put(metricName("ok.pct" + formattedPercentile), percentileValue);
                koPercentiles.put(metricName("ko.pct" + formattedPercentile), percentileValue);
                allPercentiles.put(metricName("a.pct" + formattedPercentile), percentileValue);
            } catch (Exception e) {
                log.error("Error parsing percentile: " + percentile, e);
            }
        }

    }

    private String metricName(String name) {
        return name;
    }

    private Map<String, String> parseDimensions(String dimensionsString) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        for (String dimensionString : dimensionsString.split(";")) {
            if (StringUtils.isEmpty(dimensionsString)) continue;

            if (dimensionString.contains("=")) {
                int index = dimensionString.indexOf('=');
                dimensions.put(dimensionString.substring(0, index), dimensionString.substring(index + 1));
            } else {
                log.warn("Dimension has invalid format: " + dimensionString);
            }
        }
        return ImmutableMap.copyOf(dimensions);
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        boolean cancelState = this.timerHandle.cancel(false);
        log.debug("Canceled state:" + cancelState);

        this.scheduler.shutdown();

        try {
            this.scheduler.awaitTermination(30L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            log.error("Error waiting for end of scheduler", ex);
        }

        log.info("Sending last metrics");
        this.sendMetrics();

        reportTestsEnd();

        this.filter = null;
        this.metricsSender.destroy();

        super.teardownTest(context);
    }

    private void reportTestsStart() {
        metricsSender.addEvent("started", testTitle);
    }

    private void reportTestsEnd() {
        metricsSender.addEvent("ended", testTitle);
    }

    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        arguments.addArgument(SIGNAL_FX_ENDPOINT, SIGNAL_FX_DEFAULT_ENDPOINT);
        arguments.addArgument(AUTH_TOKEN, "");
        arguments.addArgument(DIMENSIONS, "app=jmeter");
        arguments.addArgument(SUMMARY_ONLY, "false");
        arguments.addArgument(SAMPLERS_LIST, ".*");
        arguments.addArgument(USE_REGEXP_FOR_SAMPLERS_LIST, "true");
        arguments.addArgument(PERCENTILES, PERCENTILES_DEFAULT);
        arguments.addArgument(TEST_TITLE, TEST_TITLE_DEFAULT);
        arguments.addArgument(EVENT_TAGS, "");
        arguments.addArgument(SIGNALFX_TIMEOUT, Integer.toString(HttpDataPointProtobufReceiverFactory.DEFAULT_TIMEOUT_MS));
        return arguments;
    }
}
