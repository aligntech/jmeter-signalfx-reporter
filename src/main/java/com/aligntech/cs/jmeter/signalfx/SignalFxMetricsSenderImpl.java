/*
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

import com.google.common.base.Throwables;
import com.signalfx.endpoint.SignalFxEndpoint;
import com.signalfx.metrics.auth.StaticAuthToken;
import com.signalfx.metrics.connection.HttpDataPointProtobufReceiverFactory;
import com.signalfx.metrics.connection.HttpEventProtobufReceiverFactory;
import com.signalfx.metrics.errorhandler.MetricError;
import com.signalfx.metrics.errorhandler.MetricErrorType;
import com.signalfx.metrics.errorhandler.OnSendErrorHandler;
import com.signalfx.metrics.flush.AggregateMetricSender;
import com.signalfx.metrics.protobuf.SignalFxProtocolBuffers;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

import java.net.URI;
import java.util.*;

import static com.signalfx.metrics.errorhandler.MetricErrorType.DATAPOINT_SEND_ERROR;
import static com.signalfx.metrics.errorhandler.MetricErrorType.EVENT_SEND_ERROR;

public class SignalFxMetricsSenderImpl implements SignalFxMetricsSender, OnSendErrorHandler {

    private static final Logger log = LoggingManager.getLoggerForClass();

    private final AggregateMetricSender sender;
    private final List<SignalFxProtocolBuffers.Dimension> dimensions;
    private final Map<String, String> eventDimensions;

    public SignalFxMetricsSenderImpl(String endpoint, String authToken,
                                     Map<String, String> dimensions,
                                     Map<String, String> eventDimensions, int timeout) {
        URI uri = toUri(endpoint);

        sender = new AggregateMetricSender("jmeter",
                new HttpDataPointProtobufReceiverFactory(new SignalFxEndpoint(uri.getScheme(), uri.getHost(), uri.getPort())).setTimeoutMs(timeout),
                new HttpEventProtobufReceiverFactory(new SignalFxEndpoint(uri.getScheme(), uri.getHost(), uri.getPort())).setTimeoutMs(timeout),
                new StaticAuthToken(authToken), Collections.singletonList(this));
        this.dimensions = new ArrayList<>(dimensions.size());
        for (Map.Entry<String, String> d : dimensions.entrySet()) {
            this.dimensions.add(dimension(d).build());
        }
        this.eventDimensions = eventDimensions;
    }

    private URI toUri(String endpoint) {
        try {
            return new URI(endpoint);
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        }
    }

    @Override
    public void addEvent(String event, String details) {
        AggregateMetricSender.Session session = sender.createSession();
        SignalFxProtocolBuffers.Event.Builder builder = SignalFxProtocolBuffers.Event.newBuilder()
                .setEventType("Jmeter: " + event)
                .setCategory(SignalFxProtocolBuffers.EventCategory.USER_DEFINED)
                .setTimestamp(System.currentTimeMillis())
                .addDimensions(
                        dimension("source", "jmeter"))
                .addProperties(
                        SignalFxProtocolBuffers.Property.newBuilder()
                                .setKey("details")
                                .setValue(
                                        SignalFxProtocolBuffers.PropertyValue.newBuilder()
                                                .setStrValue(details)
                                                .build())
                                .build());
        for (Map.Entry<String, String> eventDimensions : eventDimensions.entrySet()) {
            builder.addDimensions(dimension(eventDimensions));
        }
        session.setEvent(builder.build());
        closeQuitely(session);
    }

    private SignalFxProtocolBuffers.Dimension.Builder dimension(Map.Entry<String, String> entry) {
        return dimension(entry.getKey(), entry.getValue());
    }

    private SignalFxProtocolBuffers.Dimension.Builder dimension(String key, String value) {
        return SignalFxProtocolBuffers.Dimension.newBuilder().setKey(key).setValue(value);
    }

    @Override
    public MetricCollector startCollection() {
        return new MetricCollectorImpl(sender.createSession(), dimensions);
    }

    @Override
    public void writeAndSendMetrics(MetricCollector collector) {
        if (!(collector instanceof MetricCollectorImpl)) {
            throw new IllegalArgumentException("This sender can work only with " + MetricCollectorImpl.class);
        }
        closeQuitely(((MetricCollectorImpl) collector).session);
    }

    private void closeQuitely(AggregateMetricSender.Session session) {
        try {
            session.close();
        } catch (Exception ex) {
            //to be handled in handleError(MetricError) method
        }
    }

    @Override
    public void destroy() {
        log.debug("Destroing signalfx sender");
    }

    @Override
    public void handleError(MetricError metricError) {
        Set<MetricErrorType> warnErrors = EnumSet.of(DATAPOINT_SEND_ERROR,
                EVENT_SEND_ERROR);
        if (warnErrors.contains(metricError.getMetricErrorType())) {
            log.warn(metricError.getMessage(), metricError.getException());
        } else {
            log.error(metricError.getMessage(), metricError.getException());
        }
    }

    private static class MetricCollectorImpl implements MetricCollector {

        private final long timestamp;
        private final AggregateMetricSender.Session session;
        private final List<SignalFxProtocolBuffers.Dimension> dimensions;

        public MetricCollectorImpl(AggregateMetricSender.Session session, List<SignalFxProtocolBuffers.Dimension> dimensions) {
            this.session = session;
            this.timestamp = System.currentTimeMillis();
            this.dimensions = dimensions;
        }

        @Override
        public void addGauge(String contextName, String measurement, int value) {
            session.setDatapoint(metric(contextName, measurement)
                    .setMetricType(SignalFxProtocolBuffers.MetricType.GAUGE)
                    .setValue(SignalFxProtocolBuffers.Datum.newBuilder().setIntValue(value))
                    .build()
            );
        }

        @Override
        public void addGauge(String contextName, String measurement, double value) {
            if (Double.isInfinite(value) || Double.isNaN(value)) {
                return;
            }
            session.setDatapoint(metric(contextName, measurement)
                    .setMetricType(SignalFxProtocolBuffers.MetricType.GAUGE)
                    .setValue(SignalFxProtocolBuffers.Datum.newBuilder().setDoubleValue(value))
                    .build()
            );
        }

        @Override
        public void addCounter(String contextName, String measurement, int value) {
            session.setDatapoint(metric(contextName, measurement)
                    .setMetricType(SignalFxProtocolBuffers.MetricType.COUNTER)
                    .setValue(SignalFxProtocolBuffers.Datum.newBuilder().setIntValue(normalizeValue(value)))
                    .build()
            );
        }

        /**
         * Normalization of integer value. For some reason passing of value without multiplication results in
         * strange graphed values
         */
        private int normalizeValue(int value) {
            return value * 10;
        }

        private SignalFxProtocolBuffers.DataPoint.Builder metric(String contextName, String measurement) {
            return SignalFxProtocolBuffers.DataPoint.newBuilder()
                    .setTimestamp(timestamp)
                    .setSource("jmeter")
                    .setMetric("jmeter." + contextName + "." + measurement)
                    .addAllDimensions(dimensions);
        }
    }
}
