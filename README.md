# Features
It allows to send all JMeter data to SignalFX and also report events about start and end of tests

This listener can be used with JMeter 3.1

# Parameters
Parameters allows you to specify where to send metrics and what to actually send

| Parameter | Description | Values |  Default Value |
|---|---|---|---| 
| signalFxEndpoint|Endpoint where to send metrics| | https://ingest.signalfx.com:443 |
| authToken	| Auth token to use when sending metrics. Get one in SignalFX web app	| auth token string ||	
|dimensions	|SignalFX dimensions.	|Semicolon separated list of key=value pairs	|app=jmeter|
|summaryOnly|	Should only "all" samplers be reported|	true/false|	false|
|samplersList|	List of samplers that should be reported (or regex)|	Semicolon separated list of samplers or regex (if useRegexpForSamplersList is true)|	.*|
|useRegexpForSamplersList|	Regex to match reported samplers |	true/false	| true |
|percentiles|	Percentiles that should be reported | 	Semicolon separated list of percentile values	| 90;95;99|
|testTitle|	Title for event started, recorded as property	| String that will help you to identify your test in signal fx events	| Test name |
|eventTags|	Dimensions for "events" about start and end of test	|Semicolon separated list of key=value pairs ||	

Example parameters equal to what was used to send metrics to Influx/Grafana

# Example of dimensions and event titles
|Parameter| Value | 
|---|---|
|dimensions|	app=backend;project=project_x;env=pre-prod;app_loc=usw2;gen_loc=use1 |
|testTitle|	Project X: Load round one |

# Installation

1. `mvn clean package`
2. Copy `target/signalfx-listener.jar` to `lib/ext` folder of your JMeter installation
3. After that you can add backend listener to your project and select SignalFX in combobox

## Note

JMeter 3.1 does not "memoize" settings when you switch one listener to another, so be careful with 
switching from Graphite to SignalFX and bax