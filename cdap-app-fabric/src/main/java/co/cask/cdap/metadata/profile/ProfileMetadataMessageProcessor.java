/*
 * Copyright © 2018 Cask Data, Inc.
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


package co.cask.cdap.metadata.profile;

import co.cask.cdap.api.app.ApplicationSpecification;
import co.cask.cdap.api.data.DatasetContext;
import co.cask.cdap.api.metadata.MetadataScope;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.config.ConfigDataset;
import co.cask.cdap.config.ConfigNotFoundException;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.metadata.dataset.MetadataDataset;
import co.cask.cdap.data2.metadata.store.DefaultMetadataStore;
import co.cask.cdap.data2.metadata.writer.MetadataMessage;
import co.cask.cdap.internal.app.runtime.SystemArguments;
import co.cask.cdap.internal.app.runtime.schedule.ProgramSchedule;
import co.cask.cdap.internal.app.runtime.schedule.store.ProgramScheduleStoreDataset;
import co.cask.cdap.internal.app.runtime.schedule.store.Schedulers;
import co.cask.cdap.internal.app.store.AppMetadataStore;
import co.cask.cdap.internal.app.store.ApplicationMeta;
import co.cask.cdap.metadata.MetadataMessageProcessor;
import co.cask.cdap.proto.NamespaceMeta;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.codec.EntityIdTypeAdapter;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.InstanceId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.NamespacedEntityId;
import co.cask.cdap.proto.id.ParentedId;
import co.cask.cdap.proto.id.ProfileId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.ScheduleId;
import co.cask.cdap.proto.id.WorkflowId;
import co.cask.cdap.store.NamespaceMDS;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Class to process the profile metadata request
 */
public class ProfileMetadataMessageProcessor implements MetadataMessageProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(ProfileMetadataMessageProcessor.class);
  private static final Type SET_ENTITY_TYPE = new TypeToken<Set<EntityId>>() { }.getType();
  private static final String PROFILE_METADATA_KEY = "profile";

  private static final Gson GSON = new GsonBuilder()
                                     .registerTypeAdapter(EntityId.class, new EntityIdTypeAdapter())
                                     .create();

  private final NamespaceMDS namespaceMDS;
  private final AppMetadataStore appMetadataStore;
  private final ProgramScheduleStoreDataset scheduleDataset;
  private final ConfigDataset configDataset;
  private final MetadataDataset metadataDataset;

  public ProfileMetadataMessageProcessor(CConfiguration cConf, DatasetContext datasetContext,
                                         DatasetFramework datasetFramework) {
    namespaceMDS = NamespaceMDS.getNamespaceMDS(datasetContext, datasetFramework);
    appMetadataStore = AppMetadataStore.create(cConf, datasetContext, datasetFramework);
    scheduleDataset = Schedulers.getScheduleStore(datasetContext, datasetFramework);
    configDataset = ConfigDataset.get(datasetContext, datasetFramework);
    metadataDataset = DefaultMetadataStore.getMetadataDataset(datasetContext, datasetFramework, MetadataScope.SYSTEM);
  }

  @Override
  public void processMessage(MetadataMessage message) {
    EntityId entityId = message.getEntityId();

    switch (message.getType()) {
      case PROFILE_UPDATE:
        updateProfileMetadata(entityId, message);
        break;
      case PROILE_REMOVE:
        removeProfileMetadata(message.getPayload(GSON, SET_ENTITY_TYPE));
        break;
      default:
        // This shouldn't happen
        LOG.warn("Unknown message type for profile metadata update. Ignoring the message {}", message);
    }
  }

  private void updateProfileMetadata(EntityId entityId, MetadataMessage message) {
    switch (entityId.getEntityType()) {
      case INSTANCE:
        for (NamespaceMeta meta : namespaceMDS.list()) {
          updateProfileMetadata(meta.getNamespaceId(), message);
        }
        break;
      case NAMESPACE:
        NamespaceId namespaceId = (NamespaceId) entityId;
        // make sure namespace exists before updating
        if (namespaceMDS.get(namespaceId) == null) {
          LOG.warn("Namespace {} is not found, so the profile metadata of programs or schedules in it will not get " +
                     "updated. Ignoring the message {}", namespaceId, message);
          return;
        }
        for (ApplicationMeta meta : appMetadataStore.getAllApplications(namespaceId.getNamespace())) {
          updateAppProfileMetadata(namespaceId.app(meta.getId()), meta.getSpec());
        }
        break;
      case APPLICATION:
        ApplicationId appId = (ApplicationId) entityId;
        // make sure app exists before updating
        ApplicationMeta meta = appMetadataStore.getApplication(appId);
        if (meta == null) {
          LOG.warn("Application {} is not found, so the profile metadata of its programs/schedules will not get " +
                     "updated. Ignoring the message {}", appId, message);
          return;
        }
        updateAppProfileMetadata(appId, meta.getSpec());
        break;
      case PROGRAM:
        ProgramId programId = (ProgramId) entityId;
        // make sure the app of the program exists before updating
        meta = appMetadataStore.getApplication(programId.getParent());
        if (meta == null) {
          LOG.warn("Application {} is not found, so the profile metadata of program {} will not get updated. " +
                     "Ignoring the message {}", programId.getParent(), programId, message);
          return;
        }

        // Now we only support profile on Workflow type
        if (programId.getType().equals(ProgramType.WORKFLOW)) {
          updateProgramProfileMetadata(programId);
        }
        break;
      case SCHEDULE:
        ScheduleId scheduleId = (ScheduleId) entityId;
        // make sure the schedule exists before updating
        try {
          ProgramSchedule schedule = scheduleDataset.getSchedule(scheduleId);
          updateScheduleProfileMetadata(schedule, getResolvedProfileId(schedule.getProgramId()));
        } catch (NotFoundException e) {
          LOG.warn("Schedule {} is not found, so its profile metadata will not get updated. " +
                     "Ignoring the message {}", scheduleId, message);
          return;
        }

        break;
      default:
        // this should not happen
        LOG.warn("Type of the entity id {} cannot be used to update profile metadata. " +
                   "Ignoring the message {}", entityId, message);
    }
  }

  private void removeProfileMetadata(Set<EntityId> entityIds) {
    for (EntityId entityId : entityIds) {
      // now profile metadata will only be on the program or schedule
      if (entityId instanceof ProgramId || entityId instanceof ScheduleId) {
        metadataDataset.removeProperties((NamespacedEntityId) entityId, PROFILE_METADATA_KEY);
      }
    }
  }

  private void updateAppProfileMetadata(ApplicationId applicationId, ApplicationSpecification appSpec) {
    for (String name : appSpec.getWorkflows().keySet()) {
      WorkflowId programId = applicationId.workflow(name);
      updateProgramProfileMetadata(programId);
    }
  }

  private void updateProgramProfileMetadata(ProgramId programId) {
    ProfileId profileId = getResolvedProfileId(programId);
    setProfileMetadata(programId, profileId);

    for (ProgramSchedule schedule : scheduleDataset.listSchedules(programId)) {
      updateScheduleProfileMetadata(schedule, profileId);
    }
  }

  private void updateScheduleProfileMetadata(ProgramSchedule schedule, @Nullable ProfileId profileId) {
    ScheduleId scheduleId = schedule.getScheduleId();
    Optional<ProfileId> scheduleProfileId =
      SystemArguments.getProfileIdFromArgs(scheduleId.getNamespaceId(), schedule.getProperties());
    profileId = scheduleProfileId.isPresent() ? scheduleProfileId.get() : profileId;
    setProfileMetadata(scheduleId, profileId);
  }

  private void setProfileMetadata(NamespacedEntityId entityId, @Nullable ProfileId profileId) {
    // if we are able to get profile from preferences or schedule properties, use it
    // otherwise default profile will be used
    metadataDataset.setProperty(entityId, PROFILE_METADATA_KEY,
                                profileId == null ? ProfileId.DEFAULT.toString() : profileId.toString());
  }

  // TODO: CDAP-13579 consider preference key starts with [scope].[name].system.profile.name
  @Nullable
  private ProfileId getResolvedProfileId(EntityId entityId) {
    if (entityId instanceof InstanceId) {
      return SystemArguments.getProfileIdFromArgs(NamespaceId.SYSTEM, getPreferences("", entityId)).orElse(null);
    }

    if (entityId instanceof NamespacedEntityId) {
      NamespaceId namespaceId = ((NamespacedEntityId) entityId).getNamespaceId();
      Optional<ProfileId> profile =
        SystemArguments.getProfileIdFromArgs(namespaceId, getPreferences(namespaceId.getNamespace(), entityId));

      // if we are able to find the profile, return it
      if (profile.isPresent()) {
        return profile.get();
      }

      // if the entity id has a parent id, get the preference from its parent
      if (entityId instanceof ParentedId) {
        return getResolvedProfileId(((ParentedId) entityId).getParent());
      } else {
        // otherwise it is a namespace id, which we want to look at the instance level
        return getResolvedProfileId(new InstanceId(""));
      }
    }
    return null;
  }

  private Map<String, String> getPreferences(String namespace, EntityId entityId) {
    try {
      return configDataset.get(namespace, "preferences", entityId.toString()).getProperties();
    } catch (ConfigNotFoundException e) {
      // it is ok that no preference is set
      return Collections.emptyMap();
    }
  }
}
