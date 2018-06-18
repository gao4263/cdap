/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.client;

import co.cask.cdap.api.artifact.ArtifactRange;
import co.cask.cdap.api.metadata.MetadataEntity;
import co.cask.cdap.api.metadata.MetadataScope;
import co.cask.cdap.client.common.ClientTestBase;
import co.cask.cdap.common.metadata.MetadataRecord;
import co.cask.cdap.common.metadata.MetadataRecordV2;
import co.cask.cdap.proto.element.EntityTypeSimpleName;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramRunId;
import co.cask.cdap.proto.id.StreamId;
import co.cask.cdap.proto.metadata.MetadataSearchResponse;
import co.cask.cdap.proto.metadata.MetadataSearchResponseV2;
import co.cask.cdap.proto.metadata.MetadataSearchResultRecord;
import co.cask.cdap.proto.metadata.lineage.CollapseType;
import co.cask.cdap.proto.metadata.lineage.LineageRecord;
import org.junit.Assert;
import org.junit.Before;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.jar.Manifest;
import javax.annotation.Nullable;

/**
 * Base class for metadata tests.
 */
public abstract class MetadataTestBase extends ClientTestBase {

  protected static final NamespaceId TEST_NAMESPACE1 = new NamespaceId("testnamespace1");

  private MetadataClient metadataClient;
  private LineageClient lineageClient;
  protected ArtifactClient artifactClient;
  protected NamespaceClient namespaceClient;
  protected ApplicationClient appClient;
  protected ProgramClient programClient;
  protected StreamClient streamClient;
  protected StreamViewClient streamViewClient;
  protected DatasetClient datasetClient;

  @Before
  public void beforeTest() throws IOException {
    metadataClient = new MetadataClient(getClientConfig());
    lineageClient = new LineageClient(getClientConfig());
    artifactClient = new ArtifactClient(getClientConfig());
    namespaceClient = new NamespaceClient(getClientConfig());
    appClient = new ApplicationClient(getClientConfig());
    programClient = new ProgramClient(getClientConfig());
    streamClient = new StreamClient(getClientConfig());
    streamViewClient = new StreamViewClient(getClientConfig());
    datasetClient = new DatasetClient(getClientConfig());
  }

  protected void addAppArtifact(ArtifactId artifactId, Class<?> cls) throws Exception {
      artifactClient.add(artifactId, null, () -> Files.newInputStream(createAppJarFile(cls).toPath()));
  }

  protected void addPluginArtifact(ArtifactId artifactId, Class<?> cls, Manifest manifest,
                                   @Nullable Set<ArtifactRange> parents) throws Exception {
    artifactClient.add(artifactId, parents, () -> Files.newInputStream(createArtifactJarFile(cls, manifest).toPath()));
  }

  protected void addProperties(MetadataEntity metadataEntity, @Nullable Map<String, String> properties)
    throws Exception {
    metadataClient.addProperties(metadataEntity, properties);
  }
  protected void addProperties(EntityId entityId, @Nullable Map<String, String> properties) throws Exception {
    addProperties(entityId.toMetadataEntity(), properties);
  }

  protected void addProperties(final EntityId entityId, @Nullable final Map<String, String> properties,
                               Class<? extends Exception> expectedExceptionClass) {
    expectException((Callable<Void>) () -> {
      addProperties(entityId, properties);
      return null;
    }, expectedExceptionClass);
  }

  protected void addProperties(final MetadataEntity metadataEntity, @Nullable final Map<String, String> properties,
                               Class<? extends Exception> expectedExceptionClass) throws IOException {
    expectException((Callable<Void>) () -> {
      addProperties(metadataEntity, properties);
      return null;
    }, expectedExceptionClass);
  }

  protected Set<MetadataRecordV2> getMetadata(MetadataEntity metadataEntity) throws Exception {
    return getMetadata(metadataEntity, null);
  }

  protected Set<MetadataRecordV2> getMetadata(MetadataEntity metadataEntity, @Nullable MetadataScope scope)
    throws Exception {
    return metadataClient.getMetadata(metadataEntity, scope);
  }

  protected Set<MetadataRecord> getMetadata(EntityId entityId) throws Exception {
    return getMetadata(entityId, null);
  }

  protected Set<MetadataRecord> getMetadata(EntityId entityId, @Nullable MetadataScope scope) throws Exception {
    return metadataClient.getMetadata(entityId, scope);
  }

  protected Map<String, String> getProperties(MetadataEntity metadataEntity, MetadataScope scope) throws Exception {
    return metadataClient.getProperties(metadataEntity, scope);
  }

  protected Map<String, String> getProperties(EntityId entityId, MetadataScope scope) throws Exception {
    return getProperties(entityId.toMetadataEntity(), scope);
  }

  protected void removeMetadata(MetadataEntity metadataEntity) throws Exception {
    metadataClient.removeMetadata(metadataEntity);
  }

  protected void removeMetadata(EntityId entityId) throws Exception {
    removeMetadata(entityId.toMetadataEntity());
  }

  protected void removeProperties(MetadataEntity metadataEntity) throws Exception {
    metadataClient.removeProperties(metadataEntity);
  }

  protected void removeProperties(EntityId entityId) throws Exception {
    removeProperties(entityId.toMetadataEntity());
  }

  public void removeProperty(EntityId entityId, String propertyToRemove) throws Exception {
    removeProperty(entityId.toMetadataEntity(), propertyToRemove);
  }

  private void removeProperty(MetadataEntity metadataEntity, String propertyToRemove) throws Exception {
    metadataClient.removeProperty(metadataEntity, propertyToRemove);
  }

  protected void addTags(final MetadataEntity metadataEntity, @Nullable final Set<String> tags,
                         Class<? extends Exception> expectedExceptionClass) throws IOException {
    expectException((Callable<Void>) () -> {
      addTags(metadataEntity, tags);
      return null;
    }, expectedExceptionClass);
  }

  protected void addTags(MetadataEntity metadataEntity, @Nullable Set<String> tags)
    throws Exception {
    metadataClient.addTags(metadataEntity, tags);
  }

  protected void addTags(EntityId entityId, @Nullable Set<String> tags)
    throws Exception {
    addTags(entityId.toMetadataEntity(), tags);
  }

  protected void addTags(final EntityId entityId, @Nullable final Set<String> tags,
                         Class<? extends Exception> expectedExceptionClass) {
    expectException((Callable<Void>) () -> {
      addTags(entityId, tags);
      return null;
    }, expectedExceptionClass);
  }

  protected Set<MetadataSearchResultRecord> searchMetadata(NamespaceId namespaceId, String query,
                                                           Set<EntityTypeSimpleName> targets) throws Exception {
    // Note: Can't delegate this to the next method. This is because MetadataHttpHandlerTestRun overrides these two
    // methods, to strip out metadata from search results for easier assertions.
    return metadataClient.searchMetadata(namespaceId, query, targets).getResults();
  }

  protected Set<MetadataSearchResultRecord> searchMetadata(NamespaceId namespaceId, String query,
                                                           Set<EntityTypeSimpleName> targets,
                                                           @Nullable String sort) throws Exception {
    return metadataClient.searchMetadata(namespaceId, query, targets,
                                         sort, 0, Integer.MAX_VALUE, 0, null, false).getResults();
  }

  protected MetadataSearchResponse searchMetadata(NamespaceId namespaceId, String query,
                                                  Set<EntityTypeSimpleName> targets,
                                                  @Nullable String sort, int offset, int limit, int numCursors,
                                                  @Nullable String cursor, boolean showHiddden) throws Exception {
    return metadataClient.searchMetadata(namespaceId, query, targets, sort, offset, limit, numCursors,
                                         cursor, showHiddden);
  }

  protected MetadataSearchResponseV2 searchMetadata(NamespaceId namespaceId, String query,
                                                    Set<EntityTypeSimpleName> targets,
                                                    @Nullable String sort, int offset, int limit, int numCursors,
                                                    @Nullable String cursor, boolean showHiddden,
                                                    boolean showCustom) throws Exception {
    return metadataClient.searchMetadata(namespaceId, query, targets, sort, offset, limit, numCursors,
                                         cursor, showHiddden, showCustom);
  }

  protected Set<String> getTags(MetadataEntity metadataEntity, MetadataScope scope) throws Exception {
    return metadataClient.getTags(metadataEntity, scope);
  }
  protected Set<String> getTags(EntityId entityId, MetadataScope scope) throws Exception {
    return getTags(entityId.toMetadataEntity(), scope);
  }

  protected void removeTag(MetadataEntity metadataEntity, String tagToRemove) throws Exception {
    metadataClient.removeTag(metadataEntity, tagToRemove);
  }

  protected void removeTags(MetadataEntity metadataEntity) throws Exception {
    metadataClient.removeTags(metadataEntity);
  }

  protected void removeTag(EntityId entityId, String tagToRemove) throws Exception {
    removeTag(entityId.toMetadataEntity(), tagToRemove);
  }

  protected void removeTags(EntityId entityId) throws Exception {
    removeTags(entityId.toMetadataEntity());
  }

  // expect an exception during fetching of lineage
  protected void fetchLineage(DatasetId datasetInstance, long start, long end, int levels,
                              Class<? extends Exception> expectedExceptionClass) throws Exception {
    fetchLineage(datasetInstance, Long.toString(start), Long.toString(end), levels, expectedExceptionClass);
  }

  // expect an exception during fetching of lineage
  protected void fetchLineage(final DatasetId datasetInstance, final String start, final String end,
                              final int levels, Class<? extends Exception> expectedExceptionClass) throws Exception {
    expectException(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        fetchLineage(datasetInstance, start, end, levels);
        return null;
      }
    }, expectedExceptionClass);
  }

  protected LineageRecord fetchLineage(DatasetId datasetInstance, long start, long end,
                                       int levels) throws Exception {
    return lineageClient.getLineage(datasetInstance, start, end, levels);
  }

  protected LineageRecord fetchLineage(DatasetId datasetInstance, long start, long end,
                                       Set<CollapseType> collapseTypes, int levels) throws Exception {
    return lineageClient.getLineage(datasetInstance, start, end, collapseTypes, levels);
  }

  protected LineageRecord fetchLineage(DatasetId datasetInstance, String start, String end,
                                       int levels) throws Exception {
    return lineageClient.getLineage(datasetInstance, start, end, levels);
  }

  protected LineageRecord fetchLineage(StreamId stream, long start, long end, int levels) throws Exception {
    return lineageClient.getLineage(stream, start, end, levels);
  }

  protected LineageRecord fetchLineage(StreamId stream, String start, String end, int levels) throws Exception {
    return lineageClient.getLineage(stream, start, end, levels);
  }

  protected LineageRecord fetchLineage(StreamId stream, long start, long end, Set<CollapseType> collapseTypes,
                                       int levels) throws Exception {
    return lineageClient.getLineage(stream, start, end, collapseTypes, levels);
  }

  protected void getPropertiesFromInvalidEntity(EntityId entityId) throws Exception {
    Map<String, String> properties = getProperties(entityId, MetadataScope.USER);
    Assert.assertTrue(properties.isEmpty());
  }

  protected void assertRunMetadataNotFound(ProgramRunId run) throws Exception {
    Set<MetadataRecord> metadataRecords = getMetadata(run);
    Assert.assertEquals(0, metadataRecords.size());
  }

  private <T> void expectException(Callable<T> callable, Class<? extends Exception> expectedExceptionClass) {
    try {
      callable.call();
      Assert.fail("Expected to have exception of class: " + expectedExceptionClass);
    } catch (Exception e) {
      if (e.getClass() != expectedExceptionClass) {
        Assert.fail(String.format("Expected %s but received %s. %s", expectedExceptionClass.getSimpleName(), e
          .getClass().getSimpleName(), e));
      }
    }
  }
}
