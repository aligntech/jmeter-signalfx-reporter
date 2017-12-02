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

/**
 * Abstraction to send metrics to SignalFx similar to Graphite and SignalFx hosted in JMeter codebase
 */
interface SignalFxMetricsSender {

    /**
     * Record and send event related to test run
     * @param event event type
     * @param details description of event
     */
    void addEvent(String event, String details);

    /**
     * Creates collector to do record one set of metrics. Returned collector is <strong>not</strong> threadsafe
     * and should not be shared by multiple threads
     */
    MetricCollector startCollection();

    /**
     * Sends all metrics collected by given collector and closes it for further usage
     */
    void writeAndSendMetrics(MetricCollector collector);

    /**
     * Closes all resources associated with this sender
     */
    void destroy();

}
