/*
Copyright 2017 Align Technology

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */


package com.aligntech.cs.jmeter.signalfx;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Collects metrics of different kinds. Metrics are not automatically sent. To send metrics collector should
 * be passed to proper metric sender.
 */
@NotThreadSafe
public interface MetricCollector {
    /**
     * Record gauge (instant value) for given measurement and context
     * @param contextName section where measurement is done (e.g. test)
     * @param measurement kind of measurement (e.g. minAT)
     * @param value integer metric value
     */
    void addGauge(String contextName, String measurement, int value);

    /**
     * Record gauge (instant value) for given measurement and context
     * @param contextName section where measurement is done (e.g. all, specific measurement)
     * @param measurement kind of measurement (e.g. ok.avg)
     * @param value double metric value
     */
    void addGauge(String contextName, String measurement, double value);

    /**
     * Add value to a counter defined by given context and measurement
     * @param contextName section where measurement is done (e.g. all, specific measurement)
     * @param measurement kind of measurement (e.g. ok.avg)
     * @param value value to be added to counter
     */
    void addCounter(String contextName, String measurement, int value);
}
