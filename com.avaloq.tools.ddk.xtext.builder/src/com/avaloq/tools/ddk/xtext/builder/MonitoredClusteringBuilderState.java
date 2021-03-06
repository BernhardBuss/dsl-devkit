/*******************************************************************************
 * Copyright (c) 2016 Avaloq Evolution AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Avaloq Evolution AG - initial API and implementation
 *******************************************************************************/
package com.avaloq.tools.ddk.xtext.builder;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.common.util.WrappedException;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.osgi.util.NLS;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.builder.clustering.ClusteringBuilderState;
import org.eclipse.xtext.builder.clustering.CurrentDescriptions;
import org.eclipse.xtext.builder.impl.BuildData;
import org.eclipse.xtext.builder.resourceloader.IResourceLoader;
import org.eclipse.xtext.builder.resourceloader.IResourceLoader.LoadOperationException;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceDescription.Delta;
import org.eclipse.xtext.resource.IResourceDescription.Manager;
import org.eclipse.xtext.resource.IResourceDescriptions;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.impl.DefaultResourceDescriptionDelta;
import org.eclipse.xtext.resource.impl.ResourceDescriptionChangeEvent;
import org.eclipse.xtext.resource.impl.ResourceDescriptionsData;
import org.eclipse.xtext.service.OperationCanceledManager;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.util.Pair;
import org.eclipse.xtext.util.Tuples;

import com.avaloq.tools.ddk.xtext.build.BuildPhases;
import com.avaloq.tools.ddk.xtext.builder.layered.ILayeredResourceDescriptions;
import com.avaloq.tools.ddk.xtext.builder.layered.IXtextBuildTrigger;
import com.avaloq.tools.ddk.xtext.builder.layered.IXtextTargetPlatformManager;
import com.avaloq.tools.ddk.xtext.builder.layered.NullResourceDescriptionsData;
import com.avaloq.tools.ddk.xtext.builder.tracing.BuildFlushEvent;
import com.avaloq.tools.ddk.xtext.builder.tracing.BuildIndexingEvent;
import com.avaloq.tools.ddk.xtext.builder.tracing.BuildLinkingEvent;
import com.avaloq.tools.ddk.xtext.builder.tracing.BuildResourceSetClearEvent;
import com.avaloq.tools.ddk.xtext.builder.tracing.ClusterClosedEvent;
import com.avaloq.tools.ddk.xtext.builder.tracing.ResourceIndexingEvent;
import com.avaloq.tools.ddk.xtext.builder.tracing.ResourceLinkingEvent;
import com.avaloq.tools.ddk.xtext.builder.tracing.ResourceLinkingMemoryEvent;
import com.avaloq.tools.ddk.xtext.builder.tracing.ResourceProcessingEvent;
import com.avaloq.tools.ddk.xtext.builder.tracing.ResourceValidationEvent;
import com.avaloq.tools.ddk.xtext.extensions.AbstractResourceDescriptionsData;
import com.avaloq.tools.ddk.xtext.extensions.IResourceDescriptionsData;
import com.avaloq.tools.ddk.xtext.extensions.ResourceDescriptions2;
import com.avaloq.tools.ddk.xtext.linking.ILazyLinkingResource2;
import com.avaloq.tools.ddk.xtext.resource.AbstractCachingResourceDescriptionManager;
import com.avaloq.tools.ddk.xtext.resource.extensions.ForwardingResourceDescriptions;
import com.avaloq.tools.ddk.xtext.resource.extensions.IResourceDescriptions2;
import com.avaloq.tools.ddk.xtext.tracing.IExecutionDataCollector;
import com.avaloq.tools.ddk.xtext.tracing.ITraceSet;
import com.avaloq.tools.ddk.xtext.tracing.ResourceValidationRuleSummaryEvent;
import com.avaloq.tools.ddk.xtext.util.EmfResourceSetUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;


/**
 * Clustering builder state enhanced by tracing, flight recorder and progress monitoring watch dog.
 */
// CHECKSTYLE:COUPLING-OFF
@Singleton
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.ExcessiveClassLength"})
public class MonitoredClusteringBuilderState extends ClusteringBuilderState
    implements IResourceDescriptions2, IXtextTargetPlatformManager.Listener, ILayeredResourceDescriptions {

  public static final String PHASE_ONE_BUILD_SORTER = "com.avaloq.tools.ddk.xtext.builder.phaseOneBuildSorter"; //$NON-NLS-1$
  public static final String PHASE_TWO_BUILD_SORTER = "com.avaloq.tools.ddk.xtext.builder.phaseTwoBuildSorter"; //$NON-NLS-1$

  public static final long CANCELLATION_POLLING_TIMEOUT = 5000; // ms
  public static final long CANCELLATION_POLLING_DELAY = 200; // ms

  /** Class-wide logger. */
  private static final Logger LOGGER = Logger.getLogger(MonitoredClusteringBuilderState.class);

  @Inject
  private IExecutionDataCollector dataCollector;

  /** Cluster size. */
  @Inject(optional = true)
  @Named("org.eclipse.xtext.builder.clustering.ClusteringBuilderState.clusterSize")
  // CHECKSTYLE:CONSTANTS-OFF
  private int clusterSize = 20; // NOPMD Cannot be static because of the way Guice injection works.
  // CHECKSTYLE:CONSTANTS-ON

  @Inject
  @Named(RESOURCELOADER_GLOBAL_INDEX)
  private IResourceLoader globalIndexResourceLoader;

  @Inject
  @Named(RESOURCELOADER_CROSS_LINKING)
  private IResourceLoader crossLinkingResourceLoader;

  /** Strategy to dynamically adapt cluster sizes. */
  @Inject
  private IBuilderResourceLoadStrategy loadingStrategy;

  /** Sorter to sort the resources for phase 1. */
  @Inject
  @Named(PHASE_ONE_BUILD_SORTER)
  private IBuildSorter phaseOneBuildSorter;

  /** Sorter to sort the resources for phase 2. */
  @Inject
  @Named(PHASE_TWO_BUILD_SORTER)
  private IBuildSorter phaseTwoBuildSorter;

  /** To start a build (asynchronously) when the target platform changes. */
  @Inject
  private IXtextBuildTrigger buildTrigger;

  @Inject
  private IDescriptionCopier descriptionCopier;

  @Inject
  private IResourcePostProcessor postProcessor;

  @Inject
  private OperationCanceledManager operationCanceledManager;

  /**
   * Handle to the ResourceDescriptionsData we use viewed as a IResourceDescriptions2 (with findReferences()). Parent class does not provide direct access to
   * its ResourceDescriptionsData, and anyway it wouldn't know about out new interfaces.
   */
  private IResourceDescriptions2 myData;

  /**
   * Copied handle to the plain ResourceDescriptionsData.
   */
  private ResourceDescriptionsData rawData;

  /** Marker for null entries in the map of saved resource descriptions. */
  private static final IResourceDescription NULL_DESCRIPTION = new EmptyResourceDescriptionImpl(null);

  /**
   * Unfortunately, we have to duplicate the loading logic here in order to make it better extendable. Parent class lacks a factory method for loading and
   * creating the initial ResourceDescriptionsData. We use an {@link IResourceDescriptionsDataProvider} for that.
   */
  private volatile boolean isLoaded; // NOPMD; PMD doesn't like "volatile"...

  /**
   * Don't use a PesistedStateProvider, but use a target platform manager that gives us directly an appropriate ResourceDescriptionsData. The point here is that
   * the builder state is not interested in loading some set of resource descriptions, but only in getting its initial ResourceDescriptionsData, wherever that
   * may come from.
   */
  private final IXtextTargetPlatformManager targetPlatformManager;

  /**
   * Creates a new instance of {@link MonitoredClusteringBuilderState}.
   *
   * @param manager
   *          used to load the target platform
   */
  @Inject
  public MonitoredClusteringBuilderState(final IXtextTargetPlatformManager manager) {
    super();
    this.targetPlatformManager = manager;
    manager.addListener(this);
  }

  // note that this setter is required as otherwise the clusterSize field will be marked final
  public void setClusterSize(final int clusterSize) {
    this.clusterSize = clusterSize;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized void load() {
    if (!isLoaded) {
      setResourceDescriptionsData((ResourceDescriptionsData) targetPlatformManager.getPlatform().getIResourceDescriptionsData());
      isLoaded = true;
    }
  }

  /** {@inheritDoc} */
  @Override
  protected void ensureLoaded() {
    if (!isLoaded) {
      load();
    }
  }

  /**
   * Determine whether an index has been loaded.
   *
   * @return {@code true} if an index has been loaded, {@code false} otherwise.
   */
  protected boolean isLoaded() {
    return this.isLoaded;
  }

  @Override
  protected void setResourceDescriptionsData(final ResourceDescriptionsData newData) {
    setResourceDescriptionsData(newData, new NullProgressMonitor());
  }

  /**
   * Set the resource descriptions data, unless cancellation has been requested.
   *
   * @param newData
   *          the new resource descriptions data
   * @param monitor
   *          the monitor to check for cancellation
   */
  protected void setResourceDescriptionsData(final ResourceDescriptionsData newData, final IProgressMonitor monitor) {
    checkForCancellation(monitor);
    rawData = newData;
    if (newData instanceof IResourceDescriptions2) {
      myData = (IResourceDescriptions2) newData;
    } else {
      myData = new ResourceDescriptions2(newData);
    }
    super.setResourceDescriptionsData(newData);
    if (isLoaded && newData instanceof AbstractResourceDescriptionsData) {
      ((AbstractResourceDescriptionsData) newData).commitChanges();
    }
    isLoaded = true;
  }

  @Override
  protected ResourceDescriptionsData getCopiedResourceDescriptionsData() {
    ResourceDescriptionsData copy = super.getCopiedResourceDescriptionsData();
    if (copy instanceof AbstractResourceDescriptionsData) {
      ((AbstractResourceDescriptionsData) copy).beginChanges();
    }
    return copy;
  }

  /**
   * Provide access to the index.
   *
   * @return the index.
   */
  protected ResourceDescriptionsData getResourceDescriptionsData() {
    ensureLoaded();
    return rawData;
  }

  @Override
  @SuppressWarnings("PMD.AvoidInstanceofChecksInCatchClause")
  public synchronized ImmutableList<Delta> update(final BuildData buildData, final IProgressMonitor monitor) {
    ensureLoaded();
    final SubMonitor subMonitor = SubMonitor.convert(monitor, org.eclipse.xtext.builder.builderState.Messages.AbstractBuilderState_0, 1);
    subMonitor.subTask(org.eclipse.xtext.builder.builderState.Messages.AbstractBuilderState_0);

    checkForCancellation(monitor);

    final ResourceDescriptionsData newData = getCopiedResourceDescriptionsData();
    Collection<IResourceDescription.Delta> result = null;
    try {
      result = doUpdate(buildData, newData, subMonitor.newChild(1));
      // update the reference
      setResourceDescriptionsData(newData, monitor);
      // CHECKSTYLE:OFF
    } catch (Throwable t) {
      // CHECKSTYLE:ON
      if (!operationCanceledManager.isOperationCanceledException(t)) {
        LOGGER.error("Failed to update index. Executing rollback.", t); //$NON-NLS-1$
      }
      if (newData instanceof AbstractResourceDescriptionsData) {
        ((AbstractResourceDescriptionsData) newData).rollbackChanges();
      }
      throw t;
    }

    final ResourceDescriptionChangeEvent event = new ResourceDescriptionChangeEvent(result);
    notifyListeners(event);
    return event.getDeltas();
  }

  @Override
  public synchronized ImmutableList<IResourceDescription.Delta> clean(final Set<URI> toBeRemoved, final IProgressMonitor monitor) {
    ensureLoaded();
    Set<URI> toBeRemovedCopy = ensureNotNull(toBeRemoved);

    SubMonitor subMonitor = SubMonitor.convert(monitor, org.eclipse.xtext.builder.builderState.Messages.AbstractBuilderState_0, 2);
    subMonitor.subTask(org.eclipse.xtext.builder.builderState.Messages.AbstractBuilderState_0);
    if (toBeRemovedCopy.isEmpty()) {
      return ImmutableList.of();
    }
    checkForCancellation(monitor);
    Collection<IResourceDescription.Delta> deltas = doClean(toBeRemovedCopy, subMonitor.newChild(1));

    final ResourceDescriptionsData newData = getCopiedResourceDescriptionsData();
    ResourceDescriptionChangeEvent event = null;
    try {
      checkForCancellation(monitor);
      for (IResourceDescription.Delta delta : deltas) {
        newData.removeDescription(delta.getOld().getURI());
      }
      event = new ResourceDescriptionChangeEvent(deltas);
      checkForCancellation(monitor);
      updateDeltas(event.getDeltas(), null, subMonitor.newChild(1));
      // update the reference
      setResourceDescriptionsData(newData, monitor);
      // CHECKSTYLE:OFF
    } catch (Throwable t) {
      // CHECKSTYLE:ON
      if (newData instanceof AbstractResourceDescriptionsData) {
        ((AbstractResourceDescriptionsData) newData).rollbackChanges();
      }
      throw t;
    }

    notifyListeners(event);
    return event.getDeltas();
  }

  /** {@inheritDoc} */
  @Override
  // CHECKSTYLE:CHECK-OFF NestedTryDepth
  protected Collection<Delta> doUpdate(final BuildData buildData, final ResourceDescriptionsData newData, final IProgressMonitor monitor) { // NOPMD
    final SubMonitor progress = SubMonitor.convert(monitor, 100);

    // Step 1: Clean the set of deleted URIs. If any of them are also added, they're not deleted.
    final Set<URI> toBeDeleted = Sets.newHashSet(buildData.getToBeDeleted());
    toBeDeleted.removeAll(buildData.getToBeUpdated());

    ResourceSet resourceSet = buildData.getResourceSet();

    // Step 2: Create a new state (old state minus the deleted resources). This, by virtue of the flag above
    // and a Guice binding, is the index that is used during the build; i.e., linking during the build will
    // use this. Once the build is completed, the persistable index is reset to the contents of newState by
    // virtue of the newMap, which is maintained in synch with this.
    final CurrentDescriptions2 newState = createCurrentDescriptions(resourceSet, newData);

    final Map<URI, IResourceDescription> oldDescriptions = saveOldDescriptions(buildData);
    // Step 3: Create a queue; write new temporary resource descriptions for the added or updated resources
    // so that we can link subsequently; put all the added or updated resources into the queue.
    // CHECKSTYLE:CONSTANTS-OFF
    writeNewResourceDescriptions(buildData, this, newState, newData, progress.newChild(20));
    // CHECKSTYLE:CONSTANTS-ON

    // clear resource set to wipe out derived state of phase 1 model inference and all corresponding references
    clearResourceSet(resourceSet);

    LOGGER.info(Messages.MonitoredClusteringBuilderState_PHASE_ONE_DONE);
    checkForCancellation(progress);

    // Step 4: Create a URI set of resources not yet in the delta. This is used for queuing; whenever a resource is
    // queued for processing, its URI is removed from this set. queueAffectedResources will consider only resources
    // in this set as potential candidates. Make sure that notInDelta is a modifiable Set, not some immutable view.
    for (final URI uri : toBeDeleted) {
      checkForCancellation(monitor);
      newData.removeDescription(uri);
    }
    final Set<URI> allRemainingURIs = createCandidateSet(newData.getAllURIs());
    allRemainingURIs.removeAll(buildData.getToBeUpdated());
    for (URI remainingURI : buildData.getAllRemainingURIs()) {
      allRemainingURIs.remove(remainingURI);
    }
    flushChanges(newData);

    // Our return value. It contains all the deltas resulting from this build.
    final Set<Delta> allDeltas = Sets.newHashSet();

    // Step 5: Put all resources depending on a deleted resource into the queue. Also register the deltas in allDeltas.
    if (!toBeDeleted.isEmpty()) {
      addDeletedURIsToDeltas(toBeDeleted, allDeltas, oldDescriptions);
      // Here, we have only the deltas for deleted resources in allDeltas. Make sure that all markers are removed.
      // Normally, resources in toBeDeleted will have their storage(s) deleted, so Eclipse will automatically
      // remove the markers. However, if the ToBeBuiltComputer adds resources to the tobeDeleted set that are not or
      // have not been physically removed, markers would otherwise remain even though the resource is no longer part
      // of the Xtext world (index). Since the introduction of IResourcePostProcessor, we also need to do this to give
      // the post-processor a chance to do whatever needs doing when a resource is removed.
      updateDeltas(allDeltas, resourceSet, progress.newChild(1));
    }
    // Add all pending deltas to all deltas (may be scheduled java deltas)
    Collection<Delta> pendingDeltas = buildData.getAndRemovePendingDeltas();
    allDeltas.addAll(pendingDeltas);
    queueAffectedResources(allRemainingURIs, this, newState, allDeltas, allDeltas, buildData, progress.newChild(1));

    IProject currentProject = getBuiltProject(buildData);
    IResourceLoader.LoadOperation loadOperation = null;

    final BuilderWatchdog watchdog = new BuilderWatchdog();

    try {
      dataCollector.started(BuildLinkingEvent.class);
      Queue<URI> queue = buildData.getURIQueue();
      loadOperation = crossLinkingResourceLoader.create(resourceSet, currentProject);
      loadOperation.load(queue);

      // Step 6: Iteratively got through the queue. For each resource, create a new resource description and queue all depending
      // resources that are not yet in the delta. Validate resources. Do this in chunks.
      final SubMonitor subProgress = progress.newChild(80);
      final CancelIndicator cancelMonitor = new CancelIndicator() {
        @Override
        public boolean isCanceled() {
          return subProgress.isCanceled();
        }
      };

      watchdog.start();
      int index = 1;
      while (!queue.isEmpty()) {
        // CHECKSTYLE:CONSTANTS-OFF
        subProgress.setWorkRemaining(queue.size() * 3);
        // CHECKSTYLE:CONSTANTS-ON
        final List<Delta> newDeltas = Lists.newArrayListWithExpectedSize(clusterSize);
        final List<Delta> changedDeltas = Lists.newArrayListWithExpectedSize(clusterSize);
        while (!queue.isEmpty() && loadingStrategy.mayProcessAnotherResource(resourceSet, newDeltas.size())) {
          if (subProgress.isCanceled() || !loadOperation.hasNext()) {
            if (!loadOperation.hasNext()) {
              LOGGER.warn(Messages.MonitoredClusteringBuilderState_NO_MORE_RESOURCES);
            }
            loadOperation.cancel();
            throw new OperationCanceledException();
          }
          // Load the resource and create a new resource description
          URI changedURI = null;
          Resource resource = null;
          Delta newDelta = null;

          final long initialMemory = Runtime.getRuntime().freeMemory();
          final int initialResourceSetSize = resourceSet.getResources().size();
          final long initialTime = System.nanoTime();
          try {
            // Load the resource and create a new resource description
            resource = addResource(loadOperation.next().getResource(), resourceSet);
            changedURI = resource.getURI();
            dataCollector.started(ResourceProcessingEvent.class, changedURI);
            queue.remove(changedURI);
            if (toBeDeleted.contains(changedURI)) {
              break;
            }

            watchdog.reportWorkStarted(changedURI);
            dataCollector.started(ResourceLinkingEvent.class, changedURI);
            final IResourceDescription.Manager manager = getResourceDescriptionManager(changedURI);
            if (manager != null) {
              final Object[] bindings = {Integer.valueOf(index), Integer.valueOf(index + queue.size()), URI.decode(resource.getURI().lastSegment())};
              subProgress.subTask(NLS.bind(Messages.MonitoredClusteringBuilderState_UPDATE_DESCRIPTIONS, bindings));
              // Resolve links here!
              try {
                EcoreUtil2.resolveLazyCrossReferences(resource, cancelMonitor);
                final IResourceDescription description = manager.getResourceDescription(resource);
                final IResourceDescription copiedDescription = descriptionCopier.copy(description);
                newDelta = manager.createDelta(getSavedResourceDescription(oldDescriptions, changedURI), copiedDescription);
              } catch (StackOverflowError ex) {
                queue.remove(changedURI);
                LOGGER.warn(NLS.bind(Messages.MonitoredClusteringBuilderState_COULD_NOT_PROCESS_DUE_TO_STACK_OVERFLOW_ERROR, changedURI));
              }
            }
            // CHECKSTYLE:OFF guard against ill behaved implementations
          } catch (final Exception ex) {
            // CHECKSTYLE:ON
            pollForCancellation(monitor);
            if (changedURI == null && ex instanceof LoadOperationException) { // NOPMD
              changedURI = ((LoadOperationException) ex).getUri();
            }
            queue.remove(changedURI);
            LOGGER.error(NLS.bind(Messages.MonitoredClusteringBuilderState_CANNOT_LOAD_RESOURCE, changedURI), ex);
            if (resource != null) {
              resourceSet.getResources().remove(resource);
            }
            final IResourceDescription oldDescription = getSavedResourceDescription(oldDescriptions, changedURI);
            if (oldDescription != null) {
              newDelta = new DefaultResourceDescriptionDelta(oldDescription, null);
            }
          } finally {
            dataCollector.ended(ResourceLinkingEvent.class);
          }

          if (newDelta != null) {
            allDeltas.add(newDelta);
            if (newDelta.haveEObjectDescriptionsChanged()) {
              changedDeltas.add(newDelta);
            }
            if (recordDeltaAsNew(newDelta)) {
              newDeltas.add(newDelta);
              // Make the new resource description known in the new index.
              newState.register(newDelta);
            }
            try {
              // Validate directly, instead of bulk validating after the cluster.
              updateMarkers(newDelta, resourceSet, subProgress.newChild(1, SubMonitor.SUPPRESS_ALL_LABELS));
              postProcess(newDelta, resourceSet, subProgress.newChild(1));
            } catch (StackOverflowError ex) {
              queue.remove(changedURI);
              LOGGER.warn(NLS.bind(Messages.MonitoredClusteringBuilderState_COULD_NOT_PROCESS_DUE_TO_STACK_OVERFLOW_ERROR, changedURI));
            }
          } else {
            subProgress.worked(2);
          }

          if (changedURI != null) {
            final long memoryDelta = Runtime.getRuntime().freeMemory() - initialMemory;
            final int resourceSetSizeDelta = resourceSet.getResources().size() - initialResourceSetSize;
            final long timeDelta = System.nanoTime() - initialTime;
            dataCollector.trace(ResourceLinkingMemoryEvent.class, changedURI, memoryDelta, resourceSetSizeDelta, timeDelta);
            watchdog.reportWorkEnded(index, index + queue.size());
          }

          // Clear caches of resource
          if (resource instanceof XtextResource) {
            ((XtextResource) resource).getCache().clear(resource);
          }
          dataCollector.ended(ResourceProcessingEvent.class);
          subProgress.worked(1);
          index++;
        }

        loadOperation.cancel();

        queueAffectedResources(allRemainingURIs, this, newState, changedDeltas, allDeltas, buildData, subProgress.newChild(1));
        newDeltas.clear();
        changedDeltas.clear();

        if (queue.size() > 0) {
          loadOperation = crossLinkingResourceLoader.create(resourceSet, currentProject);
          loadOperation.load(queue);
        }

        if (!queue.isEmpty()) {
          dataCollector.trace(ClusterClosedEvent.class, Long.valueOf(resourceSet.getResources().size()));
          clearResourceSet(resourceSet);
        }
        // TODO flush required here or elsewhere ?
        // flushChanges(newData);
      }
    } finally {
      // Report the current size of the resource set
      dataCollector.trace(ClusterClosedEvent.class, Long.valueOf(resourceSet.getResources().size()));
      if (loadOperation != null) {
        loadOperation.cancel();
      }
      dataCollector.ended(BuildLinkingEvent.class);
      watchdog.interrupt();
    }
    return allDeltas;
    // CHECKSTYLE:CHECK-ON NestedTryDepth
  }

  /**
   * Updates the markers on a single resource.
   *
   * @param delta
   *          for the changed resource
   * @param resourceSet
   *          containing the resource
   * @param monitor
   *          to report progress
   */
  @Override
  protected void updateMarkers(final Delta delta, final ResourceSet resourceSet, final IProgressMonitor monitor) {
    ResourceValidationRuleSummaryEvent.Collector traceCollector = null;
    try {
      dataCollector.started(ResourceValidationEvent.class, delta.getUri());
      if (dataCollector instanceof ITraceSet) {
        traceCollector = ResourceValidationRuleSummaryEvent.Collector.addToLoadOptions(resourceSet);
      }
      super.updateMarkers(delta, resourceSet, monitor);
    } finally {
      if (dataCollector instanceof ITraceSet) {
        traceCollector.postEvents(delta.getUri(), (ITraceSet) dataCollector);
      }
      dataCollector.ended(ResourceValidationEvent.class);
    }
  }

  /**
   * Updates the markers on a collection of resource.
   *
   * @param deltas
   *          for the changed resources
   * @param resourceSet
   *          containing the resource
   * @param monitor
   *          to report progress
   */
  protected void updateDeltas(final Collection<Delta> deltas, final ResourceSet resourceSet, final IProgressMonitor monitor) {
    SubMonitor progress = SubMonitor.convert(monitor, deltas.size() * 2);
    for (Delta delta : deltas) {
      checkForCancellation(monitor);
      updateMarkers(delta, resourceSet, progress.newChild(1));
      postProcess(delta, resourceSet, progress.newChild(1));
    }
  }

  /**
   * Post-processes a delta.
   *
   * @param delta
   *          to process
   * @param resourceSet
   *          to use for resource loading (the builder's resourceSet)
   * @param monitor
   *          to report progress and handle cancellation
   */
  protected void postProcess(final Delta delta, final ResourceSet resourceSet, final IProgressMonitor monitor) {
    postProcessor.process(delta, resourceSet, monitor);
  }

  /**
   * Return the old resource description; from {@code savedDescriptions}, if available.
   *
   * @param savedDescriptions
   *          a map of saved resource descriptions
   * @param uri
   *          the URI of the resource description wanted
   * @return The resource description, or null if non-existent.
   */
  protected IResourceDescription getSavedResourceDescription(final Map<URI, IResourceDescription> savedDescriptions, final URI uri) {
    if (uri == null) {
      return null;
    }
    IResourceDescription saved = savedDescriptions.remove(uri);
    if (saved == null) {
      // TODO DSL-828: this may end up using a lot of memory; we should instead consider creating old copies of the resources in the db
      IResourceDescription old = getResourceDescription(uri);
      saved = old != null ? new FixedCopiedResourceDescription(old) : null;
    } else if (saved == NULL_DESCRIPTION) { // NOPMD
      saved = null; // NOPMD
    }
    return saved;
  }

  /**
   * Save copies of existing resource descriptions (which will be overwritten in the first build phase). We only copy the EObjectDescriptions.
   *
   * @param buildData
   *          The buildData
   * @return a map containing all existing resource descriptions, with NULL_DESCRIPTION for non-existing descriptions.
   */
  protected Map<URI, IResourceDescription> saveOldDescriptions(final BuildData buildData) {
    Map<URI, IResourceDescription> cache = Maps.newHashMapWithExpectedSize(buildData.getToBeUpdated().size());
    for (URI uri : Iterables.concat(buildData.getToBeUpdated(), buildData.getToBeDeleted())) {
      if (cache.containsKey(uri)) {
        continue;
      }
      IResourceDescription desc = getResourceDescription(uri);
      if (desc == null) {
        cache.put(uri, NULL_DESCRIPTION);
      } else {
        cache.put(uri, new FixedCopiedResourceDescription(desc)); // Do *not* use descriptionCopier here, we just want the EObjectDescriptions!
      }
    }
    return cache;
  }

  /**
   * Determine whether a newly computed delta shall be recorded as new. This default implementation always returns true;
   * subclasses may override as appropriate (for instance, returning false if the two resource descriptions are structurally identical).
   *
   * @param newDelta
   *          the newly computed delta
   * @return true, if the new delta is to be registered in this state; false otherwise.
   */
  protected boolean recordDeltaAsNew(final Delta newDelta) {
    return true;
  }

  // NO-PMD'ed because of Npath complexity.
  /** {@inheritDoc} */
  protected void writeNewResourceDescriptions(final BuildData buildData, final IResourceDescriptions oldState, final CurrentDescriptions newState, final ResourceDescriptionsData newData, final IProgressMonitor monitor) { // NOPMD
    int index = 1;
    final Collection<URI> toWrite = phaseOneBuildSorter.sort(buildData.getToBeUpdated(), oldState);
    final Collection<URI> toBuild = Lists.newLinkedList();
    ResourceSet resourceSet = buildData.getResourceSet();
    BuildPhases.setIndexing(resourceSet, true);
    final List<URI> tryAgain = Lists.newLinkedList();
    int nofRetries = 0;
    int n = toWrite.size();
    final SubMonitor subMonitor = SubMonitor.convert(monitor, Messages.MonitoredClusteringBuilderState_WRITE_DESCRIPTIONS, n);

    IProject currentProject = getBuiltProject(buildData);
    IResourceLoader.LoadOperation loadOperation = null;
    try {
      dataCollector.started(BuildIndexingEvent.class);
      while (!toWrite.isEmpty()) {
        n = toWrite.size();
        index = 1;

        loadOperation = globalIndexResourceLoader.create(resourceSet, currentProject);
        loadOperation.load(toWrite);

        // Not using the loadingStrategy here; seems to work fine with a reasonable clusterSize (20 by default), even with
        // large resources and "scarce" memory (say, about 500MB).
        while (loadOperation.hasNext()) {
          if (subMonitor.isCanceled()) {
            loadOperation.cancel();
            throw new OperationCanceledException();
          }
          URI uri = null;
          Resource resource = null;
          try {
            resource = addResource(loadOperation.next().getResource(), resourceSet);
            uri = resource.getURI();
            final Object[] bindings = {Integer.valueOf(index), Integer.valueOf(n), URI.decode(resource.getURI().lastSegment())};
            subMonitor.subTask(NLS.bind(Messages.MonitoredClusteringBuilderState_WRITE_ONE_DESCRIPTION, bindings));
            dataCollector.started(ResourceIndexingEvent.class, uri);

            final IResourceDescription.Manager manager = getResourceDescriptionManager(uri);
            if (manager != null) {
              final IResourceDescription description = manager.getResourceDescription(resource);
              // We don't care here about links, we really just want the exported objects so that we can link in the next phase.
              // Set flag to make unresolvable cross-references raise an error
              resourceSet.getLoadOptions().put(ILazyLinkingResource2.MARK_UNRESOLVABLE_XREFS, Boolean.FALSE);
              final IResourceDescription copiedDescription = new FixedCopiedResourceDescription(description);

              final boolean hasUnresolvedLinks = resourceSet.getLoadOptions().get(ILazyLinkingResource2.MARK_UNRESOLVABLE_XREFS) == Boolean.TRUE;
              if (hasUnresolvedLinks) {
                tryAgain.add(uri);
                resourceSet.getResources().remove(resource);
              } else {
                final Delta intermediateDelta = manager.createDelta(oldState.getResourceDescription(uri), copiedDescription);
                newState.register(intermediateDelta);
                toBuild.add(uri);
              }
            }
          } catch (final WrappedException ex) {
            pollForCancellation(monitor);
            if (uri == null && ex instanceof LoadOperationException) { // NOPMD
              uri = ((LoadOperationException) ex).getUri();
            }
            LOGGER.error(NLS.bind(Messages.MonitoredClusteringBuilderState_CANNOT_LOAD_RESOURCE, uri), ex);
            if (resource != null) {
              resourceSet.getResources().remove(resource);
            }
            if (uri != null) {
              final IResourceDescription oldDescription = oldState.getResourceDescription(uri);
              if (oldDescription != null) {
                newState.register(new DefaultResourceDescriptionDelta(oldDescription, null));
              }
            }
            // CHECKSTYLE:OFF
            // If we couldn't load it, there's no use trying again: do not add it to the queue
          } catch (final Throwable e) {
            // unfortunately the parser sometimes crashes (yet unreported Xtext bug)
            // CHECKSTYLE:ON
            pollForCancellation(monitor);
            LOGGER.error(NLS.bind(Messages.MonitoredClusteringBuilderState_CANNOT_LOAD_RESOURCE, uri), e);
            if (resource != null) {
              resourceSet.getResources().remove(resource);
            }
          } finally {
            // Clear caches of resource
            if (resource instanceof XtextResource) {
              ((XtextResource) resource).getCache().clear(resource);
            }
            dataCollector.ended(ResourceIndexingEvent.class);
            subMonitor.worked(1);
          }

          if (!loadingStrategy.mayProcessAnotherResource(resourceSet, resourceSet.getResources().size())) {
            clearResourceSet(resourceSet);
          }
          index++;
        }

        flushChanges(newData);
        toWrite.clear();

        if (!tryAgain.isEmpty()) {
          if (tryAgain.size() != nofRetries) {
            // We made some progress.
            nofRetries = tryAgain.size();
            LOGGER.info(NLS.bind(Messages.MonitoredClusteringBuilderState_TRY_AGAIN, nofRetries, tryAgain));
            toWrite.addAll(tryAgain);
            tryAgain.clear();
          } else {
            LOGGER.warn(NLS.bind(Messages.MonitoredClusteringBuilderState_TRY_AGAIN_FAILED, nofRetries, tryAgain));
          }
        }
      }
    } finally {
      if (loadOperation != null) {
        loadOperation.cancel();
      }
      // Clear the flags
      BuildPhases.setIndexing(resourceSet, false);
      resourceSet.getLoadOptions().remove(ILazyLinkingResource2.MARK_UNRESOLVABLE_XREFS);
      for (final URI uri : phaseTwoBuildSorter.sort(toBuild, oldState)) {
        buildData.queueURI(uri);
      }
      dataCollector.ended(BuildIndexingEvent.class);
    }
  }

  /**
   * Clears the content of the resource set without sending notifications.
   * This avoids unnecessary, explicit unloads.
   *
   * @param resourceSet
   *          resource set to clear
   */
  @Override
  protected void clearResourceSet(final ResourceSet resourceSet) {
    dataCollector.started(BuildResourceSetClearEvent.class, resourceSet.getResources().size());
    try {
      EmfResourceSetUtil.clearResourceSetWithoutNotifications(resourceSet);
    } finally {
      dataCollector.ended(BuildResourceSetClearEvent.class);
    }
  }

  /**
   * Flushes the changes (added / removed resources) to the database without committing them.
   *
   * @param newData
   *          resource descriptions data
   */
  protected void flushChanges(final ResourceDescriptionsData newData) {
    if (newData instanceof IResourceDescriptionsData) {
      try {
        dataCollector.started(BuildFlushEvent.class);
        ((IResourceDescriptionsData) newData).flushChanges();
      } finally {
        dataCollector.ended(BuildFlushEvent.class);
      }
    }
  }

  /**
   * Wrapper class that caches the findAllReferencingResources() and findExactReferencingResources() queries.
   * queueAffectedResources() will call each resourceDescriptionManager, who is then responsible of figuring out
   * which of the resources it handles is affected by resources in the delta.
   * Typically, resource description managers use the referencing resources obtained through these two queries and
   * build their list of affected resources from that. Now, we cannot make these queries once for all here and
   * then let the resource description managers postprocess that global dependency information. The trouble is that
   * each of these managers belongs to a different language, and each may have its own idea of visibility. Each may
   * have its own container state. Some or all may also share the same or have identical container states.
   * If the visibility rules differ from language to language, there's no way but to let each resource description
   * manager determine itself how to compute which resources are affected. To still avoid making too many queries
   * if several or all languages share container information, we do cache identical queries here. This is in
   * particular the case in ACF, where all languages and thus all resource description managers share one global
   * container state. In this case, this caching here is very effective: each query will be executed exactly once
   * during queueAffectedResources().
   */
  private static class FindReferenceCachingState extends ForwardingResourceDescriptions {

    protected FindReferenceCachingState(final IResourceDescriptions2 baseDescriptions) {
      super(baseDescriptions);
    }

    private final Map<Pair<Set<IResourceDescription>, ReferenceMatchPolicy>, Iterable<IResourceDescription>> findAllReferencingResourcesCache = Maps.newHashMap();

    @Override
    public Iterable<IResourceDescription> findAllReferencingResources(final Set<IResourceDescription> targetResources, final ReferenceMatchPolicy matchPolicy) {
      Pair<Set<IResourceDescription>, ReferenceMatchPolicy> key = Tuples.create(targetResources, matchPolicy);
      Iterable<IResourceDescription> result = findAllReferencingResourcesCache.get(key);
      if (result == null) {
        result = Lists.newArrayList(delegate().findAllReferencingResources(targetResources, matchPolicy));
        findAllReferencingResourcesCache.put(key, result);
      }
      return result;
    }

    private final Map<Pair<Set<IEObjectDescription>, ReferenceMatchPolicy>, Iterable<IResourceDescription>> findExactReferencingResourcesCache = Maps.newHashMap();

    @Override
    public Iterable<IResourceDescription> findExactReferencingResources(final Set<IEObjectDescription> targetObjects, final ReferenceMatchPolicy matchPolicy) {
      Pair<Set<IEObjectDescription>, ReferenceMatchPolicy> key = Tuples.create(targetObjects, matchPolicy);
      Iterable<IResourceDescription> result = findExactReferencingResourcesCache.get(key);
      if (result == null) {
        result = Lists.newArrayList(delegate().findExactReferencingResources(targetObjects, matchPolicy));
        findExactReferencingResourcesCache.put(key, result);
      }
      return result;
    }

  }

  /** {@inheritDoc} */
  @Override
  protected void queueAffectedResources(final Set<URI> allRemainingURIs, final IResourceDescriptions oldState, final CurrentDescriptions newState, final Collection<Delta> changedDeltas, final Collection<Delta> allDeltas, final BuildData buildData, final IProgressMonitor monitor) {
    if (allDeltas.isEmpty() || allRemainingURIs.isEmpty()) {
      return;
    }
    ImmutableListMultimap<Manager, URI> candidatesByManager = getUrisByManager(allRemainingURIs);
    FindReferenceCachingState cachingIndex = new FindReferenceCachingState((IResourceDescriptions2) newState);
    final SubMonitor progressMonitor = SubMonitor.convert(monitor, candidatesByManager.keySet().size());
    for (Manager manager : candidatesByManager.keySet()) {
      Collection<Delta> deltas = changedDeltas;
      if (manager instanceof IResourceDescription.Manager.AllChangeAware) {
        deltas = allDeltas;
      }
      if (manager instanceof AbstractCachingResourceDescriptionManager) {
        checkForCancellation(monitor);
        AbstractCachingResourceDescriptionManager bulkManager = (AbstractCachingResourceDescriptionManager) manager;
        Set<URI> candidates = Sets.newHashSet(candidatesByManager.get(bulkManager));
        candidates.retainAll(allRemainingURIs);
        Collection<URI> affected = bulkManager.getAffectedResources(deltas, candidates, cachingIndex);
        for (URI uri : affected) {
          if (allRemainingURIs.remove(uri)) {
            buildData.queueURI(uri);
          }
        }
      } else {
        for (URI candidateURI : candidatesByManager.get(manager)) {
          checkForCancellation(monitor);
          if (allRemainingURIs.contains(candidateURI)) {
            boolean affected = false;
            if (manager instanceof IResourceDescription.Manager.AllChangeAware) {
              affected = ((IResourceDescription.Manager.AllChangeAware) manager).isAffectedByAny(deltas, oldState.getResourceDescription(candidateURI), cachingIndex);
            } else {
              affected = manager.isAffected(deltas, oldState.getResourceDescription(candidateURI), cachingIndex);
            }
            if (affected) {
              allRemainingURIs.remove(candidateURI);
              buildData.queueURI(candidateURI);
            }
          }
        }
      }
      progressMonitor.worked(1);
      if (allRemainingURIs.isEmpty()) {
        break;
      }
    }
  }

  /**
   * Checks if the given {@link IProgressMonitor} was cancelled.
   * <p>
   * <em>Note</em>: Throws OperationCanceledException if monitor was cancelled.
   * </p>
   *
   * @param monitor
   *          the {@link IProgressMonitor} to check, must not be {@code null}
   */
  protected void checkForCancellation(final IProgressMonitor monitor) {
    if (monitor.isCanceled()) {
      throw new OperationCanceledException();
    }
  }

  /**
   * Polls the given {@link IProgressMonitor} for cancellation until a timeout of {@link #CANCELLATION_POLLING_TIMEOUT} ms is reached.
   * <p>
   * <em>Note</em>: Throws OperationCanceledException if monitor is cancelled within the given timeout.
   * </p>
   *
   * @param monitor
   *          the {@link IProgressMonitor} to check, must not be {@code null}
   */
  private void pollForCancellation(final IProgressMonitor monitor) {
    final long endTime = System.currentTimeMillis() + CANCELLATION_POLLING_TIMEOUT;
    do {
      checkForCancellation(monitor);
      Uninterruptibles.sleepUninterruptibly(CANCELLATION_POLLING_DELAY, TimeUnit.MILLISECONDS);
    } while (System.currentTimeMillis() < endTime);
  }

  /**
   * Gets a map of URIs indexed by their {@link IResourceDescription.Manager}.
   *
   * @param uRIs
   *          the URIs to index
   * @return the map of URIs indexed by their managers
   */
  private ImmutableListMultimap<Manager, URI> getUrisByManager(final Set<URI> uRIs) {
    ImmutableListMultimap.Builder<Manager, URI> builder = ImmutableListMultimap.builder();
    for (URI uri : uRIs) {
      Manager mgr = getResourceDescriptionManager(uri);
      if (mgr != null) {
        builder.put(mgr, uri);
      }
    }
    return builder.build();
  }

  /**
   * Factory method to get the current descriptions to be used during build.
   *
   * @param resourceSet
   *          The resourceSet to be used
   * @param newData
   *          The index.
   * @return The new current descriptions.
   */
  protected CurrentDescriptions2 createCurrentDescriptions(final ResourceSet resourceSet, final ResourceDescriptionsData newData) {
    return new CurrentDescriptions2(resourceSet, newData);
  }

  /**
   * Create a set of potential candidate URIs to consider in dependency analysis. Subclasses may add additional candidate URIs.
   *
   * @param platformCandidates
   *          All URIs known in the local index except those that will be rebuilt anyway because they have been physically changed.
   * @return A modifiable Set of all these URIs.
   */
  protected Set<URI> createCandidateSet(final Set<URI> platformCandidates) {
    return Sets.newHashSet(platformCandidates);
  }

  /**
   * Add deltas for the removed resources.
   *
   * @param deletedUris
   *          URIs of the removed resources
   * @param deltas
   *          Deltas
   * @param savedDescriptions
   *          previously saved old resource descriptions
   */
  protected void addDeletedURIsToDeltas(final Set<URI> deletedUris, final Set<Delta> deltas, final Map<URI, IResourceDescription> savedDescriptions) {
    for (final URI uri : deletedUris) {
      final IResourceDescription oldDescription = getSavedResourceDescription(savedDescriptions, uri);
      if (oldDescription != null) {
        final IResourceDescription.Manager manager = getResourceDescriptionManager(uri);
        if (manager != null) {
          deltas.add(manager.createDelta(oldDescription, null));
        }
      }
    }
  }

  // IResourceDescriptions2 interface implementation

  /** {@inheritDoc} */
  @Override
  public Set<URI> getAllURIs() {
    return myData.getAllURIs();
  }

  /** {@inheritDoc} */
  @Override
  public Iterable<IResourceDescription> findAllReferencingResources(final Set<IResourceDescription> targetResources, final ReferenceMatchPolicy matchPolicy) {
    ensureLoaded();
    return myData.findAllReferencingResources(targetResources, matchPolicy);
  }

  /** {@inheritDoc} */
  @Override
  public Iterable<IResourceDescription> findExactReferencingResources(final Set<IEObjectDescription> targetObjects, final ReferenceMatchPolicy matchPolicy) {
    ensureLoaded();
    return myData.findExactReferencingResources(targetObjects, matchPolicy);
  }

  /** {@inheritDoc} */
  @Override
  public Iterable<IReferenceDescription> findReferencesToObjects(final Set<URI> targetObjects) {
    ensureLoaded();
    return myData.findReferencesToObjects(targetObjects);
  }

  /** {@inheritDoc} */
  @Override
  public Iterable<IResourceDescription> getLocalResourceDescriptions() {
    ensureLoaded();
    ResourceDescriptionsData indexData = getResourceDescriptionsData();
    if (indexData instanceof ILayeredResourceDescriptions) {
      return ((ILayeredResourceDescriptions) indexData).getLocalResourceDescriptions();
    } else {
      return indexData.getAllResourceDescriptions();
    }
  }

  /**
   * {@inheritDoc} Schedules a full clean build if the target platform changes.
   */
  @Override
  public void platformChanged(final IResourceDescriptionsData newPlatform, final Collection<Delta> deltas, final boolean mustRebuild) {
    if (newPlatform == null) {
      // Hmmm... context deactivated. Events for removing the project from the index will be generated anyway, so no build necessary.
      // TODO: check!
      setResourceDescriptionsData(new NullResourceDescriptionsData());
      return;
    }
    // Deltas?
    if (isLoaded()) {
      // If we're not loaded yet, then this event is due to the initial loading of the platform in super.load. In this case,
      // we are in a build anyway, aren't we?
      // TODO: this is pretty convoluted. We should try to disentangle this OO spaghetti code. Is it good enough to simply not notify listeners in
      // AbstractXtextTargetPlatformManager if it was the initial load?
      if (newPlatform instanceof AbstractResourceDescriptionsData) {
        ((AbstractResourceDescriptionsData) newPlatform).beginChanges();
      }
      setResourceDescriptionsData((ResourceDescriptionsData) newPlatform);
      ResourceDescriptionChangeEvent event = new ResourceDescriptionChangeEvent(deltas);
      notifyListeners(event);
    }
    if (mustRebuild) {
      buildTrigger.scheduleFullBuild();
    }
  }

  protected int getClusterSize() {
    return clusterSize;
  }

  protected IBuilderResourceLoadStrategy getLoadingStrategy() {
    return loadingStrategy;
  }

  protected IResourceLoader getCrossLinkingResourceLoader() {
    return crossLinkingResourceLoader;
  }

  protected IDescriptionCopier getDescriptionCopier() {
    return descriptionCopier;
  }
}
