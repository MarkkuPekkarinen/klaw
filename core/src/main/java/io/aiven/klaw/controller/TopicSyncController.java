package io.aiven.klaw.controller;

import io.aiven.klaw.error.KlawException;
import io.aiven.klaw.model.ApiResponse;
import io.aiven.klaw.model.SyncBackTopics;
import io.aiven.klaw.model.SyncTopicUpdates;
import io.aiven.klaw.model.SyncTopicsBulk;
import io.aiven.klaw.model.TopicInfo;
import io.aiven.klaw.model.enums.PermissionType;
import io.aiven.klaw.model.response.SyncTopicsList;
import io.aiven.klaw.service.TopicSyncControllerService;
import io.aiven.klaw.validation.PermissionAllowed;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/")
public class TopicSyncController {

  @Autowired private TopicSyncControllerService topicSyncControllerService;

  @PermissionAllowed(
      permissionAllowed = {PermissionType.SYNC_TOPICS, PermissionType.SYNC_BACK_TOPICS})
  @PostMapping(
      value = "/updateSyncTopics",
      produces = {MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<ApiResponse> updateSyncTopics(
      @RequestBody List<SyncTopicUpdates> syncTopicUpdates) throws KlawException {
    return new ResponseEntity<>(
        topicSyncControllerService.updateSyncTopics(syncTopicUpdates), HttpStatus.OK);
  }

  @PermissionAllowed(
      permissionAllowed = {PermissionType.SYNC_TOPICS, PermissionType.SYNC_BACK_TOPICS})
  @PostMapping(
      value = "/updateSyncTopicsBulk",
      produces = {MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<ApiResponse> updateSyncTopicsBulk(
      @RequestBody SyncTopicsBulk syncTopicsBulk) throws KlawException {
    return new ResponseEntity<>(
        topicSyncControllerService.updateSyncTopicsBulk(syncTopicsBulk), HttpStatus.OK);
  }

  // sync back topics
  @PermissionAllowed(
      permissionAllowed = {PermissionType.SYNC_TOPICS, PermissionType.SYNC_BACK_TOPICS})
  @RequestMapping(
      value = "/getTopicsRowView",
      method = RequestMethod.GET,
      produces = {MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<List<TopicInfo>> getTopicsRowView(
      @RequestParam("env") String envId,
      @RequestParam("pageNo") String pageNo,
      @RequestParam(value = "currentPage", defaultValue = "") String currentPage,
      @RequestParam(value = "topicnamesearch", required = false) String topicNameSearch,
      @RequestParam(value = "teamId", required = false) Integer teamId,
      @RequestParam(value = "topicType", required = false) String topicType) {
    return new ResponseEntity<>(
        topicSyncControllerService.getTopicsRowView(
            envId, pageNo, currentPage, topicNameSearch, teamId, topicType),
        HttpStatus.OK);
  }

  @PermissionAllowed(
      permissionAllowed = {PermissionType.SYNC_TOPICS, PermissionType.SYNC_BACK_TOPICS})
  @PostMapping(
      value = "/updateSyncBackTopics",
      produces = {MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<ApiResponse> updateSyncBackTopics(
      @RequestBody SyncBackTopics syncBackTopics) {
    return new ResponseEntity<>(
        topicSyncControllerService.updateSyncBackTopics(syncBackTopics), HttpStatus.OK);
  }

  @PermissionAllowed(
      permissionAllowed = {PermissionType.SYNC_TOPICS, PermissionType.SYNC_BACK_TOPICS})
  @RequestMapping(
      value = "/getSyncTopics",
      method = RequestMethod.GET,
      produces = {MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<SyncTopicsList> getSyncTopics(
      @RequestParam("env") String envId,
      @RequestParam("pageNo") String pageNo,
      @RequestParam(value = "currentPage", defaultValue = "") String currentPage,
      @RequestParam(value = "topicnamesearch", required = false) String topicNameSearch,
      @RequestParam(value = "showAllTopics", defaultValue = "false", required = false)
          String showAllTopics,
      @RequestParam(value = "resetTopicsCache", defaultValue = "false", required = false)
          boolean resetTopicsCache,
      @RequestParam(value = "isBulkOption", defaultValue = "false", required = false)
          String isBulkOption)
      throws Exception {
    if (Boolean.parseBoolean(showAllTopics)) {
      return new ResponseEntity<>(
          topicSyncControllerService.getSyncTopics(
              envId,
              pageNo,
              currentPage,
              topicNameSearch,
              showAllTopics,
              Boolean.parseBoolean(isBulkOption),
              resetTopicsCache,
              null,
              false),
          HttpStatus.OK);
    } else {
      return new ResponseEntity<>(
          topicSyncControllerService.getReconTopics(
              envId,
              pageNo,
              currentPage,
              topicNameSearch,
              showAllTopics,
              Boolean.parseBoolean(isBulkOption),
              resetTopicsCache,
              101,
              false),
          HttpStatus.OK);
    }
  }
}
