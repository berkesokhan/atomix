/*
 * Copyright 2018-present Open Networking Foundation
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
package io.atomix.cluster.discovery;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.time.Duration;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.atomix.cluster.NodeId;
import io.atomix.utils.component.Component;
import io.atomix.utils.component.Managed;
import io.atomix.utils.event.AbstractListenable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.atomix.utils.concurrent.Threads.namedThreads;

/**
 * Cluster membership provider that uses DNS SRV lookups.
 */
@Component(DnsDiscoveryConfig.class)
public class DnsDiscoveryProvider extends AbstractListenable<DiscoveryEvent>
    implements NodeDiscoveryProvider<DnsDiscoveryConfig>, Managed<DnsDiscoveryConfig> {

  public static final Type TYPE = new Type();

  /**
   * DNS node discovery provider type.
   */
  public static class Type implements NodeDiscoveryProvider.Type<DnsDiscoveryConfig> {
    private static final String NAME = "dns";

    @Override
    public String name() {
      return NAME;
    }

    @Override
    public DnsDiscoveryConfig newConfig() {
      return DnsDiscoveryConfig.newBuilder().build();
    }

    @Override
    public NodeDiscoveryProvider newProvider(DnsDiscoveryConfig config) {
      return new DnsDiscoveryProvider(config);
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(DnsDiscoveryProvider.class);

  private static final String[] ATTRIBUTES = new String[]{"SRV"};
  private static final String ATTRIBUTE_ID = "srv";

  private final ScheduledExecutorService resolverScheduler = Executors.newSingleThreadScheduledExecutor(
      namedThreads("atomix-cluster-dns-resolver", LOGGER));

  private String service;
  private Duration resolutionInterval;
  private DnsDiscoveryConfig config;
  private final Map<NodeId, Node> nodes = Maps.newConcurrentMap();

  private DnsDiscoveryProvider() {
  }

  public DnsDiscoveryProvider(String service) {
    this(DnsDiscoveryConfig.newBuilder().setService(service).build());
  }

  DnsDiscoveryProvider(DnsDiscoveryConfig config) {
    this.config = checkNotNull(config, "config cannot be null");
    this.service = checkNotNull(config.getService(), "service cannot be null");
    this.resolutionInterval = config.hasResolutionInterval()
        ? Duration.ofSeconds(config.getResolutionInterval().getSeconds())
        .plusNanos(config.getResolutionInterval().getNanos())
        : Duration.ofSeconds(15);
  }

  @Override
  public DnsDiscoveryConfig config() {
    return config;
  }

  @Override
  public Set<Node> getNodes() {
    return ImmutableSet.copyOf(nodes.values());
  }

  private void resolveNodes() {
    final Hashtable<String, String> env = new Hashtable<>();
    env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
    env.put("java.naming.provider.url", "dns:");

    try {
      final DirContext context = new InitialDirContext(env);
      final NamingEnumeration<?> resolved = context.getAttributes(service, ATTRIBUTES).get(ATTRIBUTE_ID).getAll();

      Set<NodeId> currentNodeIds = ImmutableSet.copyOf(nodes.keySet());
      Set<NodeId> newNodeIds = Sets.newHashSet();
      while (resolved.hasMore()) {
        String record = (String) resolved.next();
        String[] items = record.split(" ", -1);
        String host = items[3].trim();
        String port = items[2].trim();
        String id = Splitter.on('.').splitToList(host).get(0);

        Node node = Node.newBuilder()
            .setId(id)
            .setNamespace(service)
            .setHost(host)
            .setPort(Integer.parseInt(port))
            .build();

        if (nodes.putIfAbsent(NodeId.from(node.getId(), node.getNamespace()), node) == null) {
          newNodeIds.add(NodeId.from(node.getId(), node.getNamespace()));
          LOGGER.info("Node joined: {}", node);
          post(DiscoveryEvent.newBuilder()
              .setType(DiscoveryEvent.Type.JOIN)
              .setNode(node)
              .build());
        }
      }

      for (NodeId nodeId : currentNodeIds) {
        if (!newNodeIds.contains(nodeId)) {
          Node node = nodes.remove(nodeId);
          if (node != null) {
            LOGGER.info("Node left: {}", node);
            post(DiscoveryEvent.newBuilder()
                .setType(DiscoveryEvent.Type.LEAVE)
                .setNode(node)
                .build());
          }
        }
      }
    } catch (NamingException e) {
      LOGGER.debug("Failed to resolve DNS SRV record {}", service, e);
    }
  }

  @Override
  public CompletableFuture<Void> start(DnsDiscoveryConfig config) {
    LOGGER.info("Joined");
    this.config = checkNotNull(config, "config cannot be null");
    this.service = checkNotNull(config.getService(), "service cannot be null");
    this.resolutionInterval = config.hasResolutionInterval()
        ? Duration.ofSeconds(config.getResolutionInterval().getSeconds())
        .plusNanos(config.getResolutionInterval().getNanos())
        : Duration.ofSeconds(15);
    resolverScheduler.scheduleAtFixedRate(
        this::resolveNodes, 0, resolutionInterval.toMillis(), TimeUnit.MILLISECONDS);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> stop() {
    LOGGER.info("Left");
    resolverScheduler.shutdownNow();
    return CompletableFuture.completedFuture(null);
  }
}