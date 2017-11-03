package com.mesosphere.sdk.testing;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;

import org.apache.mesos.SchedulerDriver;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mesosphere.sdk.dcos.Capabilities;
import com.mesosphere.sdk.offer.evaluate.PodInfoBuilder;
import com.mesosphere.sdk.scheduler.AbstractScheduler;
import com.mesosphere.sdk.scheduler.DefaultScheduler;
import com.mesosphere.sdk.scheduler.SchedulerConfig;
import com.mesosphere.sdk.scheduler.plan.DefaultPodInstance;
import com.mesosphere.sdk.specification.ConfigFileSpec;
import com.mesosphere.sdk.specification.DefaultServiceSpec;
import com.mesosphere.sdk.specification.PodInstance;
import com.mesosphere.sdk.specification.PodSpec;
import com.mesosphere.sdk.specification.PortSpec;
import com.mesosphere.sdk.specification.ResourceSpec;
import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.specification.TaskSpec;
import com.mesosphere.sdk.specification.yaml.RawServiceSpec;
import com.mesosphere.sdk.specification.yaml.TemplateUtils;
import com.mesosphere.sdk.state.ConfigStore;
import com.mesosphere.sdk.state.StateStore;
import com.mesosphere.sdk.storage.MemPersister;
import com.mesosphere.sdk.storage.Persister;

/**
 * Exercises the service's packaging and Service Specification YAML file by building a Scheduler object against it, then
 * optionally running a series of {@link SimulationTick}s against the result.
 */
public class ServiceTestRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceTestRunner.class);
    private static final Random RANDOM = new Random();

    /**
     * Common environment variables that are injected into tasks automatically by the cluster.
     * We inject test values when exercising config rendering
     */
    private static final Map<String, String> DCOS_TASK_ENVVARS;
    static {
        DCOS_TASK_ENVVARS = new HashMap<>();
        DCOS_TASK_ENVVARS.put("MESOS_SANDBOX", "/path/to/mesos/sandbox");
        DCOS_TASK_ENVVARS.put("MESOS_CONTAINER_IP", "999.987.654.321");
        DCOS_TASK_ENVVARS.put("STATSD_UDP_HOST", "999.123.456.789");
        DCOS_TASK_ENVVARS.put("STATSD_UDP_PORT", "99999");
    }

    private final File specPath;
    private File configTemplateDir;
    private final Map<String, String> cosmosOptions;
    private final Map<String, String> buildTemplateParams;
    private final Map<String, String> customSchedulerEnv;
    private final Map<String, Map<String, String>> customPodEnvs;

    /**
     * Creates a new instance against the default {@code svc.yml} Service Specification YAML file.
     *
     * <p>WARNING: If you do not invoke the {@link test()} method, your test will not run!
     */
    public ServiceTestRunner() {
        this("svc.yml");
    }

    /**
     * Creates a new instance against the provided Service Specification YAML path.
     *
     * <p>WARNING: If you do not invoke the {@link #run()} method, your test will not run!
     *
     * @param specPath path to the Service Specification YAML file, relative to the {@code dist} directory
     */
    public ServiceTestRunner(String specPath) {
        this.specPath = getDistFile(specPath);
        this.configTemplateDir = this.specPath.getParentFile();
        this.cosmosOptions = new HashMap<>();
        this.buildTemplateParams = new HashMap<>();
        this.customSchedulerEnv = new HashMap<>();
        this.customPodEnvs = new HashMap<>();
    }

    /**
     * Configures the test with custom options as would be provided via an {@code options.json} file. If this is not
     * invoked then the service defaults from {@code config.json} are used for the test.
     *
     * @param optionKeyVals an even number of strings which will be unpacked as (key, value, key, value, ...), where the
     *     keys should be in {@code section1.section2.section3.name} form as an {@code options.json} file would have
     * @return {@code this}
     */
    public ServiceTestRunner setOptions(String... optionKeyVals) {
        this.cosmosOptions.putAll(toMap(optionKeyVals));
        return this;
    }

    /**
     * Configures the test with custom template parameters to be applied against the Universe packaging, as would be
     * provided via {@code TEMPLATE_X} envvars when building the service. These are applied onto {@code config.json}
     * and {@code resource.json} for the test
     *
     * @param paramKeyVals an even number of strings which will be unpacked as (key, value, key, value, ...)
     * @return {@code this}
     */
    public ServiceTestRunner setBuildTemplateParams(String... paramKeyVals) {
        this.buildTemplateParams.putAll(toMap(paramKeyVals));
        return this;
    }

    /**
     * Configures the test with a custom configuration template location. By default config templates are expected to
     * be in the same directory as the Service Specification YAML file.
     *
     * @return {@code this}
     */
    public ServiceTestRunner setConfigTemplateDir(File configTemplateDir) {
        this.configTemplateDir = configTemplateDir;
        return this;
    }

    /**
     * Configures the test with additional environment variables in the Scheduler beyond those which would be included
     * by the service's {@code marathon.json.mustache}. This may be useful for tests against custom Service
     * Specification YAML files which reference envvars that aren't also present in the packaging's Marathon definition.
     * These values will override any produced by the service's packaging.
     *
     * @param schedulerEnvKeyVals an even number of strings which will be unpacked as (key, value, key, value, ...)
     * @return {@code this}
     */
    public ServiceTestRunner setSchedulerEnv(String... schedulerEnvKeyVals) {
        this.customSchedulerEnv.putAll(toMap(schedulerEnvKeyVals));
        return this;
    }

    /**
     * Configures the test with additional environment variables in the specified Pod, beyond those which would be
     * included by the Scheduler or by Mesos. This may be useful for services whose tasks configure environment
     * variables before rendering config files via execution of {@code bootstrap}.
     *
     * @param podType the pod to set the environment against
     * @param podEnvKeyVals an even number of strings which will be unpacked as (key, value, key, value, ...)
     * @return {@code this}
     */
    public ServiceTestRunner setPodEnv(String podType, String... podEnvKeyVals) {
        Map<String, String> podEnv = this.customPodEnvs.get(podType);
        if (podEnv == null) {
            podEnv = new HashMap<>();
            this.customPodEnvs.put(podType, podEnv);
        }
        podEnv.putAll(toMap(podEnvKeyVals));
        return this;
    }

    /**
     * Exercises the service's packaging and resulting Service Specification YAML file without running any simulation
     * afterwards.
     *
     * @return a {@link SchedulerConfigResult} containing the resulting scheduler environment and spec information
     * @throws Exception if the test failed
     */
    public SchedulerConfigResult run() throws Exception {
        return run(Collections.emptyList());
    }

    /**
     * Exercises the service's packaging and resulting Service Specification YAML file, then runs the provided
     * simulation ticks, if any are provided.
     *
     * @return a {@link SchedulerConfigResult} containing the resulting scheduler environment and spec information
     * @throws Exception if the test failed
     */
    public SchedulerConfigResult run(Collection<SimulationTick> ticks) throws Exception {
        SchedulerConfig mockSchedulerConfig = Mockito.mock(SchedulerConfig.class);
        Mockito.when(mockSchedulerConfig.getExecutorURI()).thenReturn("test-executor-uri");
        Mockito.when(mockSchedulerConfig.getLibmesosURI()).thenReturn("test-libmesos-uri");
        Mockito.when(mockSchedulerConfig.getJavaURI()).thenReturn("test-java-uri");
        Mockito.when(mockSchedulerConfig.getApiServerPort()).thenReturn(8080);
        Mockito.when(mockSchedulerConfig.getDcosSpace()).thenReturn("test-space");

        Capabilities mockCapabilities = Mockito.mock(Capabilities.class);
        Mockito.when(mockCapabilities.supportsGpuResource()).thenReturn(true);
        Mockito.when(mockCapabilities.supportsCNINetworking()).thenReturn(true);
        Mockito.when(mockCapabilities.supportsNamedVips()).thenReturn(true);
        Mockito.when(mockCapabilities.supportsRLimits()).thenReturn(true);
        Mockito.when(mockCapabilities.supportsPreReservedResources()).thenReturn(true);
        Mockito.when(mockCapabilities.supportsFileBasedSecrets()).thenReturn(true);
        Mockito.when(mockCapabilities.supportsEnvBasedSecretsProtobuf()).thenReturn(true);
        Mockito.when(mockCapabilities.supportsEnvBasedSecretsDirectiveLabel()).thenReturn(true);
        Capabilities.overrideCapabilities(mockCapabilities);

        Map<String, String> schedulerEnvironment =
                CosmosRenderer.renderSchedulerEnvironment(cosmosOptions, buildTemplateParams);
        schedulerEnvironment.putAll(customSchedulerEnv);

        // Test 1: Does RawServiceSpec render?
        RawServiceSpec rawServiceSpec = RawServiceSpec.newBuilder(specPath)
                .setEnv(schedulerEnvironment)
                .build();

        // Test 2: Does ServiceSpec render?
        ServiceSpec serviceSpec = DefaultServiceSpec.newGenerator(
                rawServiceSpec, mockSchedulerConfig, schedulerEnvironment, configTemplateDir).build();

        // Test 3: Does the scheduler build?
        Persister persister = new MemPersister();
        AbstractScheduler scheduler = DefaultScheduler.newBuilder(serviceSpec, mockSchedulerConfig, persister)
                .setStateStore(new StateStore(persister))
                .setConfigStore(new ConfigStore<>(DefaultServiceSpec.getConfigurationFactory(serviceSpec), persister))
                .setPlansFrom(rawServiceSpec)
                .build()
                .disableThreading()
                .disableApiServer();

        // Test 4: Can we render the per-task config templates without any missing values?
        Collection<SchedulerConfigResult.TaskConfig> taskConfigs = getTaskConfigs(serviceSpec);

        SchedulerConfigResult configResult =
                new SchedulerConfigResult(serviceSpec, rawServiceSpec, schedulerEnvironment, taskConfigs);

        if (!ticks.isEmpty()) {
            // Test 5: Run simulation, if any was provided
            ClusterState state = new ClusterState(configResult, scheduler);
            SchedulerDriver mockDriver = Mockito.mock(SchedulerDriver.class);
            for (SimulationTick tick : ticks) {
                if (tick instanceof Expect) {
                    LOGGER.info("EXPECT: {}", tick.getDescription());
                    try {
                        ((Expect) tick).expect(state, mockDriver);
                    } catch (Throwable e) {
                        throw buildSimulationError(ticks, tick, e);
                    }
                } else if (tick instanceof Send) {
                    LOGGER.info("SEND:   {}", tick.getDescription());
                    ((Send) tick).send(state, mockDriver, scheduler.getMesosScheduler().get());
                } else {
                    throw new IllegalArgumentException(String.format("Unrecognized tick type: %s", tick));
                }
            }
        }

        // Reset Capabilities API to default behavior:
        Capabilities.overrideCapabilities(null);

        return configResult;
    }

    private static AssertionError buildSimulationError(
            Collection<SimulationTick> allTicks, SimulationTick failedTick, Throwable originalError) {
        StringJoiner errorRows = new StringJoiner("\n");
        errorRows.add(String.format("Expectation failed: %s", failedTick.getDescription()));
        errorRows.add("Simulation steps:");
        for (SimulationTick tick : allTicks) {
            String prefix = tick == failedTick ? ">>>FAIL<<< " : "";
            if (tick instanceof Expect) {
                prefix += "EXPECT";
            } else if (tick instanceof Send) {
                prefix += "SEND";
            } else {
                prefix += "???";
            }
            errorRows.add(String.format("- %s %s", prefix, tick.getDescription()));
        }
        // Print the original message last, because junit output will truncate based on its content:
        errorRows.add(String.format("Failure was: %s", originalError.getMessage()));
        return new AssertionError(errorRows.toString(), originalError);
    }

    private Collection<SchedulerConfigResult.TaskConfig> getTaskConfigs(ServiceSpec serviceSpec) {
        Collection<SchedulerConfigResult.TaskConfig> taskConfigs = new ArrayList<>();
        for (PodSpec podSpec : serviceSpec.getPods()) {
            PodInstance podInstance = new DefaultPodInstance(podSpec, 0);
            Map<String, String> customEnv = customPodEnvs.get(podSpec.getType());
            for (TaskSpec taskSpec : podSpec.getTasks()) {
                Map<String, String> taskEnv = getTaskEnv(serviceSpec, podInstance, taskSpec);
                if (customEnv != null) {
                    taskEnv.putAll(customEnv);
                }
                for (ConfigFileSpec configFileSpec : taskSpec.getConfigFiles()) {
                    // If your test is failing here: did you forget to include custom values via setPodEnv()?
                    String content = TemplateUtils.renderMustacheThrowIfMissing(
                            String.format("pod=%s task=%s config=%s",
                                    podSpec.getType(), taskSpec.getName(), configFileSpec.getName()),
                            configFileSpec.getTemplateContent(),
                            taskEnv);
                    taskConfigs.add(new SchedulerConfigResult.TaskConfig(
                            podSpec.getType(), taskSpec.getName(), configFileSpec.getName(), content));
                }
            }
        }
        return taskConfigs;
    }

    private static Map<String, String> getTaskEnv(ServiceSpec serviceSpec, PodInstance podInstance, TaskSpec taskSpec) {
        Map<String, String> taskEnv = new HashMap<>();
        taskEnv.putAll(PodInfoBuilder.getTaskEnvironment(serviceSpec.getName(), podInstance, taskSpec));
        taskEnv.putAll(DCOS_TASK_ENVVARS);
        // Inject envvars for any ports with envvar advertisement configured:
        for (ResourceSpec resourceSpec : taskSpec.getResourceSet().getResources()) {
            if (!(resourceSpec instanceof PortSpec)) {
                continue;
            }
            PortSpec portSpec = (PortSpec) resourceSpec;
            if (portSpec.getEnvKey() == null) {
                continue;
            }
            long portVal = portSpec.getPort();
            if (portVal == 0) {
                // Default ephemeral port range on linux is 32768 through 60999. Let's simulate that.
                // See: /proc/sys/net/ipv4/ip_local_port_range
                portVal = RANDOM.nextInt(61000 - 32768 /* result: 0 thru 28231 */) + 32768;
            }
            taskEnv.put(portSpec.getEnvKey(), String.valueOf(portVal));
        }
        return taskEnv;
    }

    /**
     * Returns a {@link File} object for a provided filename or relative path within the service's {@code src/main/dist}
     * directory.
     */
    public static File getDistFile(String specFilePath) {
        return new File(System.getProperty("user.dir") + "/src/main/dist/" + specFilePath);
    }

    private static Map<String, String> toMap(String... keyVals) {
        Map<String, String> map = new HashMap<>();
        if (keyVals.length % 2 != 0) {
            throw new IllegalArgumentException(String.format(
                    "Expected an even number of arguments [key, value, key, value, ...], got: %d",
                    keyVals.length));
        }
        for (int i = 0; i < keyVals.length; i += 2) {
            map.put(keyVals[i], keyVals[i + 1]);
        }
        return map;
    }
}