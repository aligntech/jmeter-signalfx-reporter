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

import java.util.regex.Pattern;

/**
 * Regular expression based filter. All string is considered as regular expression that is used to match
 * against sampler value
 */
class RegexSamplersFilter implements SamplersFilter {

    private final Pattern pattern;

    RegexSamplersFilter(String samplers) {
        pattern = Pattern.compile(samplers);
    }

    @Override
    public boolean metricReported(String sampler) {
        return pattern.matcher(sampler).matches();
    }
}
