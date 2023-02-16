/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.pulsaroperator.migrationtool.specs;

import com.datastax.oss.pulsaroperator.controllers.broker.BrokerResourcesFactory;
import com.datastax.oss.pulsaroperator.crds.broker.BrokerSpec;
import com.datastax.oss.pulsaroperator.crds.configs.AntiAffinityConfig;
import com.datastax.oss.pulsaroperator.crds.configs.PodDisruptionBudgetConfig;
import com.datastax.oss.pulsaroperator.crds.configs.ProbeConfig;
import com.datastax.oss.pulsaroperator.migrationtool.InputClusterSpecs;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NodeAffinity;
import io.fabric8.kubernetes.api.model.PodDNSConfig;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpec;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BrokerSpecGenerator extends BaseSpecGenerator<BrokerSpec> {

    public static final String SPEC_NAME = "Broker";
    private final String resourceName;
    private BrokerSpec generatedSpec;
    private AntiAffinityConfig antiAffinityConfig;
    private PodDNSConfig podDNSConfig;
    private boolean isRestartOnConfigMapChange;
    private String priorityClassName;
    private Map<String, Object> config;

    public BrokerSpecGenerator(InputClusterSpecs inputSpecs, KubernetesClient client) {
        super(inputSpecs, client);
        final String clusterName = inputSpecs.getClusterName();
        resourceName = BrokerResourcesFactory.getResourceName(clusterName,
                inputSpecs.getBroker().getBaseName());
        internalGenerateSpec();

    }

    @Override
    public String getSpecName() {
        return SPEC_NAME;
    }

    @Override
    public List<HasMetadata> getAllResources() {
        return resources;
    }


    @Override
    public BrokerSpec generateSpec() {
        return generatedSpec;
    }

    public void internalGenerateSpec() {
        final PodDisruptionBudget podDisruptionBudget = getPodDisruptionBudget(resourceName);
        final ConfigMap configMap = requireConfigMap(resourceName);
        final Service mainService = requireService(resourceName);
        final StatefulSet statefulSet = requireStatefulSet(resourceName);
        addResource(podDisruptionBudget);
        addResource(configMap);
        addResource(mainService);
        addResource(statefulSet);
        verifyLabelsEquals(podDisruptionBudget, statefulSet, configMap);


        final StatefulSetSpec statefulSetSpec = statefulSet.getSpec();
        final PodSpec spec = statefulSetSpec.getTemplate()
                .getSpec();
        final Container container = spec
                .getContainers()
                .get(0);
        verifyProbeCompatible(container.getReadinessProbe());
        verifyProbeCompatible(container.getLivenessProbe());
        verifyProbesSameValues(container.getReadinessProbe(), container.getLivenessProbe());
        final ProbeConfig readinessProbeConfig = createProbeConfig(container);
        PodDisruptionBudgetConfig podDisruptionBudgetConfig =
                createPodDisruptionBudgetConfig(podDisruptionBudget);


        config = new HashMap<>(configMap.getData());
        container.getEnv().forEach(envVar -> {
            config.put(envVar.getName(), envVar.getValue());
        });

        podDNSConfig = spec.getDnsConfig();
        antiAffinityConfig = createAntiAffinityConfig(spec);
        isRestartOnConfigMapChange = isPodDependantOnConfigMap(statefulSetSpec.getTemplate());
        priorityClassName = spec.getPriorityClassName();
        final NodeAffinity nodeAffinity = spec.getAffinity() == null ? null : spec.getAffinity().getNodeAffinity();
        final Map<String, String> matchLabels = getMatchLabels(statefulSetSpec);


        generatedSpec = BrokerSpec.builder()
                .annotations(statefulSet.getMetadata().getAnnotations())
                .podAnnotations(statefulSetSpec.getTemplate().getMetadata().getAnnotations())
                .image(container.getImage())
                .imagePullPolicy(container.getImagePullPolicy())
                .nodeSelectors(spec.getNodeSelector())
                .replicas(statefulSetSpec.getReplicas())
                .probe(readinessProbeConfig)
                .nodeAffinity(nodeAffinity)
                .pdb(podDisruptionBudgetConfig)
                .labels(statefulSet.getMetadata().getLabels())
                .podLabels(statefulSetSpec.getTemplate().getMetadata().getLabels())
                .matchLabels(matchLabels)
                .config(config)
                .podManagementPolicy(statefulSetSpec.getPodManagementPolicy())
                .updateStrategy(statefulSetSpec.getUpdateStrategy())
                .gracePeriod(spec.getTerminationGracePeriodSeconds() == null ? null :
                        spec.getTerminationGracePeriodSeconds().intValue())
                .resources(container.getResources())
                .service(createServiceConfig(mainService))
                .imagePullSecrets(statefulSetSpec.getTemplate().getSpec().getImagePullSecrets())
                .functionsWorkerEnabled(isFunctionsWorkerEnabled(config))
                .transactions(getTransactionCoordinatorConfig(config))
                .serviceAccountName(spec.getServiceAccountName())
                .build();
    }


    private BrokerSpec.TransactionCoordinatorConfig getTransactionCoordinatorConfig(Map<String, Object> configMapData) {
        final boolean transactionCoordinatorEnabled = boolConfigMapValue(configMapData, "functionsWorkerEnabled");
        return BrokerSpec.TransactionCoordinatorConfig.builder()
                .enabled(transactionCoordinatorEnabled)
                .build();
    }


    private boolean isFunctionsWorkerEnabled(Map<String, Object> configMapData) {
        return boolConfigMapValue(configMapData, "functionsWorkerEnabled");
    }

    @Override
    public PodDNSConfig getPodDnsConfig() {
        return podDNSConfig;
    }

    @Override
    public AntiAffinityConfig getAntiAffinityConfig() {
        return antiAffinityConfig;
    }

    @Override
    public boolean isRestartOnConfigMapChange() {
        return isRestartOnConfigMapChange;
    }

    @Override
    public String getDnsName() {
        return null;
    }

    @Override
    public String getPriorityClassName() {
        return priorityClassName;
    }
    @Override
    public Map<String, Object> getConfig() {
        return config;
    }

    private BrokerSpec.ServiceConfig createServiceConfig(Service service) {
        Map<String, String> annotations = service.getMetadata().getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>();
        }
        return BrokerSpec.ServiceConfig.builder()
                .annotations(annotations)
                .additionalPorts(removeServicePorts(service.getSpec().getPorts(),
                        BrokerResourcesFactory.DEFAULT_HTTP_PORT,
                        BrokerResourcesFactory.DEFAULT_HTTPS_PORT,
                        BrokerResourcesFactory.DEFAULT_PULSAR_PORT,
                        BrokerResourcesFactory.DEFAULT_PULSARSSL_PORT
                ))
                .type(service.getSpec().getType())
                .build();
    }


    private void verifyProbeCompatible(Probe probe) {
        if (probe.getExec() == null) {
            throw new IllegalStateException("current probe is not compatible, must be exec");
        }
    }

}
