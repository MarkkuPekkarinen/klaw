package io.aiven.klaw.service;

import static io.aiven.klaw.error.KlawErrorMessages.SYNC_102;
import static io.aiven.klaw.error.KlawErrorMessages.SYNC_ERR_101;
import static io.aiven.klaw.error.KlawErrorMessages.TOPICS_SYNC_ERR_101;
import static io.aiven.klaw.error.KlawErrorMessages.TOPICS_SYNC_ERR_102;
import static io.aiven.klaw.error.KlawErrorMessages.TOPICS_SYNC_ERR_103;
import static io.aiven.klaw.error.KlawErrorMessages.TOPICS_SYNC_ERR_104;
import static io.aiven.klaw.error.KlawErrorMessages.TOPICS_SYNC_ERR_105;
import static io.aiven.klaw.error.KlawErrorMessages.TOPICS_SYNC_ERR_106;
import static io.aiven.klaw.error.KlawErrorMessages.TOPICS_SYNC_ERR_107;
import static io.aiven.klaw.error.KlawErrorMessages.TOPICS_SYNC_ERR_108;
import static io.aiven.klaw.helpers.KwConstants.ORDER_OF_TOPIC_ENVS;
import static org.springframework.beans.BeanUtils.copyProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aiven.klaw.config.ManageDatabase;
import io.aiven.klaw.dao.CRUDResponse;
import io.aiven.klaw.dao.Env;
import io.aiven.klaw.dao.KwClusters;
import io.aiven.klaw.dao.Team;
import io.aiven.klaw.dao.Topic;
import io.aiven.klaw.dao.TopicRequest;
import io.aiven.klaw.error.KlawException;
import io.aiven.klaw.helpers.HandleDbRequests;
import io.aiven.klaw.helpers.KlawResourceUtils;
import io.aiven.klaw.helpers.Pager;
import io.aiven.klaw.helpers.UtilMethods;
import io.aiven.klaw.model.ApiResponse;
import io.aiven.klaw.model.KwMetadataUpdates;
import io.aiven.klaw.model.SyncBackTopics;
import io.aiven.klaw.model.SyncTopicUpdates;
import io.aiven.klaw.model.SyncTopicsBulk;
import io.aiven.klaw.model.TopicInfo;
import io.aiven.klaw.model.cluster.LoadTopicsResponse;
import io.aiven.klaw.model.enums.AclType;
import io.aiven.klaw.model.enums.ApiResultStatus;
import io.aiven.klaw.model.enums.ClusterStatus;
import io.aiven.klaw.model.enums.EntityType;
import io.aiven.klaw.model.enums.KafkaClustersType;
import io.aiven.klaw.model.enums.PermissionType;
import io.aiven.klaw.model.enums.RequestOperationType;
import io.aiven.klaw.model.response.SyncTopicsList;
import io.aiven.klaw.model.response.TopicConfig;
import io.aiven.klaw.model.response.TopicSyncResponseModel;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@EnableScheduling
@Service
@Slf4j
public class TopicSyncControllerService {

  @Value("${server.port}")
  private String serverPort;

  @Value("${server.servlet.context-path:}")
  private String contextPath;

  @Value("${server.ssl.key-store:null}")
  private String keyStore;

  @Autowired private ClusterApiService clusterApiService;

  @Autowired ManageDatabase manageDatabase;

  @Autowired private MailUtils mailService;

  @Autowired private CommonUtilsService commonUtilsService;

  @Autowired private ObjectMapper mapper;

  private int topicCounter = 0;

  // default at 12 am everyday
  @ConditionalOnProperty(
      name = "klaw.notify.admins.clusterchanges.scheduler.enable",
      havingValue = "true")
  @Scheduled(cron = "${klaw.notify.admins.clusterchanges.scheduler.cron.expression:0 0 0 * * ?}")
  @SchedulerLock(
      name = "TaskScheduler_NotifyAdminsOfClusterChanges",
      lockAtLeastFor = "${klaw.shedlock.lockAtLeastFor:PT30M}",
      lockAtMostFor = "${klaw.shedlock.lockAtMostFor:PT60M}")
  public void getReconTopicsScheduledAsync() {
    CompletableFuture.runAsync(this::getReconTopicsScheduled);
  }

  public void getReconTopicsScheduled() {
    Map<Integer, List<String>> envTenantMap = manageDatabase.getEnvsOfTenantsMap();
    Map<Integer, String> tenantMap = manageDatabase.getTenantMap();
    List<Integer> tenants = new ArrayList<>(envTenantMap.keySet());

    for (Integer tenantId : tenants) {
      StringBuilder reconStr = new StringBuilder();

      boolean notifyAdmin = false;

      int i = 0;
      // One email per environment if there are changes
      for (Env env : manageDatabase.getKafkaEnvList(tenantId)) {
        if (env.getEnvStatus() != ClusterStatus.ONLINE) {
          continue;
        }
        try {
          List<TopicSyncResponseModel> results =
              getReconTopics(env.getId(), "-1", "", null, "false", false, false, 101, true)
                  .getResultSet();

          if (i == 0) {
            reconStr.append("Tenant : ").append(tenantMap.get(tenantId)).append("\n");
          }

          reconStr
              .append("Topic differences in ")
              .append(env.getName())
              .append(" Klaw environment !!\n\n");

          for (TopicSyncResponseModel topicRequestModel : results) {
            notifyAdmin = true;
            if (topicRequestModel.getRemarks().equalsIgnoreCase("added")) {
              reconStr
                  .append("Topic ")
                  .append(topicRequestModel.getTopicname())
                  .append(" ")
                  .append(topicRequestModel.getRemarks().toLowerCase())
                  .append(" on Kafka cluster ")
                  .append(topicRequestModel.getEnvironmentName())
                  .append("\n");
            } else {
              reconStr
                  .append("Topic : ")
                  .append(topicRequestModel.getTopicname())
                  .append(" ")
                  .append(topicRequestModel.getRemarks().toLowerCase())
                  .append(" in Klaw environment ")
                  .append(topicRequestModel.getEnvironmentName())
                  .append("\n");
            }
          }

          reconStr
              .append("\n------------------------------------------------------------------")
              .append("\n\n");

        } catch (Exception e) {
          log.error("Exception:", e);
        }
        i++;
      }

      if (notifyAdmin) {
        mailService.sendReconMailToAdmin(
            "Reconciliation of Topics for tenant :" + manageDatabase.getTenantMap().get(tenantId),
            reconStr.toString(),
            tenantMap.get(tenantId),
            tenantId,
            getServerUrl());
      }
    }
  }

  private String getServerUrl() {
    String baseProtocol = "http";
    if (keyStore != null && !keyStore.equals("null")) {
      baseProtocol = "https";
    }
    return baseProtocol
        + "://"
        + InetAddress.getLoopbackAddress().getHostName()
        + ":"
        + serverPort
        + "/"
        + contextPath;
  }

  public SyncTopicsList getReconTopics(
      String envId,
      String pageNo,
      String currentPage,
      String topicNameSearch,
      String showAllTopics,
      boolean isBulkOption,
      boolean resetTopicsCache,
      Integer tenantId,
      boolean scheduledThread)
      throws Exception {
    SyncTopicsList syncTopicsList = new SyncTopicsList();

    SyncTopicsList loadTopicsResponse =
        getSyncTopics(
            envId,
            pageNo,
            currentPage,
            topicNameSearch,
            showAllTopics,
            isBulkOption,
            resetTopicsCache,
            tenantId,
            scheduledThread);
    List<TopicSyncResponseModel> topicRequestModelList = loadTopicsResponse.getResultSet();

    topicRequestModelList =
        topicRequestModelList.stream()
            .filter(
                topicRequestModel ->
                    topicRequestModel != null
                        && ("DELETED".equals(topicRequestModel.getRemarks())
                            || topicRequestModel.getTeamname() == null
                            || topicRequestModel.getTeamname().equals("")))
            .collect(Collectors.toList());

    int allTopicsCount = topicRequestModelList.size();

    Integer finalTenantId1 = tenantId;
    topicRequestModelList.forEach(
        topicReq -> topicReq.setEnvironmentName(getEnvDetails(envId, finalTenantId1).getName()));
    if (tenantId == null) {
      tenantId = commonUtilsService.getTenantId(getUserName());
    }

    if (!"-1".equals(pageNo)) { // scheduler call
      Integer finalTenantId = tenantId;
      topicRequestModelList =
          Pager.getItemsList(
              pageNo,
              currentPage,
              topicRequestModelList,
              (pageContext, mp) -> {
                mp.setTotalNoPages(pageContext.getTotalPages());
                mp.setAllPageNos(pageContext.getAllPageNos());
                mp.setCurrentPage(pageContext.getPageNo());
                mp.setTeamname(manageDatabase.getTeamNameFromTeamId(finalTenantId, mp.getTeamId()));
                return mp;
              });
    }

    syncTopicsList.setResultSet(topicRequestModelList);
    syncTopicsList.setAllTopicsCount(allTopicsCount);
    syncTopicsList.setAllTopicWarningsCount(
        Long.valueOf(topicRequestModelList.stream().filter(req -> !req.isValidatedTopic()).count())
            .intValue());
    syncTopicsList.setTopicsLoadingStatus(loadTopicsResponse.isTopicsLoadingStatus());
    return syncTopicsList;
  }

  public SyncTopicsList getSyncTopics(
      String env,
      String pageNo,
      String currentPage,
      String topicNameSearch,
      String showAllTopics,
      boolean isBulkOption,
      boolean resetTopicsCache,
      Integer tenantId,
      boolean scheduledThread)
      throws Exception {
    boolean isReconciliation = !Boolean.parseBoolean(showAllTopics);
    SyncTopicsList syncTopicsList = new SyncTopicsList();
    if (tenantId == null) {
      tenantId = commonUtilsService.getTenantId(getUserName());
    }
    log.info("getSyncTopics {} {} {}", env, pageNo, topicNameSearch);

    if (!"-1".equals(pageNo)) { // ignore check for scheduler
      if (commonUtilsService.isNotAuthorizedUser(
          commonUtilsService.getPrincipal(), PermissionType.SYNC_TOPICS)) {
        return null;
      }
    }

    LoadTopicsResponse loadTopicsResponse =
        getTopicsFromKafkaCluster(env, topicNameSearch, resetTopicsCache, tenantId);
    syncTopicsList.setTopicsLoadingStatus(loadTopicsResponse.isLoadingInProgress());
    List<TopicConfig> topicsList;

    topicsList =
        loadTopicsResponse.getTopicConfigSet().stream()
            .sorted(new TopicControllerService.TopicNameSyncComparator())
            .collect(Collectors.toList());

    List<TopicSyncResponseModel> deletedTopicsFromClusterList = new ArrayList<>();
    List<Integer> sizeOfTopics = new ArrayList<>();

    if (isReconciliation) {
      syncTopicsList.setResultSet(
          getSyncTopicListRecon(
              topicsList,
              deletedTopicsFromClusterList,
              env,
              isBulkOption,
              tenantId,
              scheduledThread));
      syncTopicsList.setAllTopicsCount(topicsList.size());
      syncTopicsList.setAllTopicWarningsCount(
          Long.valueOf(
                  syncTopicsList.getResultSet().stream()
                      .filter(req -> !req.isValidatedTopic())
                      .count())
              .intValue());
    } else {
      syncTopicsList.setResultSet(
          getSyncTopicList(
              topicsList,
              deletedTopicsFromClusterList,
              pageNo,
              currentPage,
              env,
              isBulkOption,
              sizeOfTopics,
              tenantId,
              scheduledThread));
      syncTopicsList.setAllTopicsCount(sizeOfTopics.get(0));
      syncTopicsList.setAllTopicWarningsCount(
          Long.valueOf(
                  syncTopicsList.getResultSet().stream()
                      .filter(req -> !req.isValidatedTopic())
                      .count())
              .intValue());
    }

    return syncTopicsList;
  }

  private List<TopicSyncResponseModel> getSyncTopicList(
      List<TopicConfig> topicsList,
      List<TopicSyncResponseModel> deletedTopicsFromClusterList,
      String pageNo,
      String currentPage,
      String env,
      boolean isBulkOption,
      List<Integer> sizeOfTopics,
      int tenantId,
      boolean scheduledThread) {
    // Get Sync topics
    List<Topic> topicsFromSOT =
        manageDatabase.getHandleDbRequests().getSyncTopics(env, null, tenantId);

    // tenant filtering
    //    topicsFromSOT = commonUtilsService.getFilteredTopicsForTenant(topicsFromSOT);
    int counterInc;
    List<String> teamList;
    teamList = tenantFilterTeams(tenantId, scheduledThread);

    if (!isBulkOption) {
      updateClusterDeletedTopicsList(
          topicsList, deletedTopicsFromClusterList, topicsFromSOT, teamList, tenantId);
    }

    List<TopicRequest> topicsListMap = new ArrayList<>();

    for (int i = 0; i < topicsList.size(); i++) {
      counterInc = counterIncrement();
      TopicRequest mp = new TopicRequest();
      if (createTopicRequest(topicsList, topicsFromSOT, teamList, i, counterInc, mp, tenantId)) {
        topicsListMap.add(mp);
      }
    }
    // topics which exist on cluster and not in kw, with no recon option.
    List<TopicSyncResponseModel> topicRequestModelList =
        getTopicSyncModels(topicsListMap, env, tenantId);
    topicRequestModelList.addAll(deletedTopicsFromClusterList);

    sizeOfTopics.add(topicRequestModelList.size());
    return Pager.getItemsList(
        pageNo,
        currentPage,
        topicRequestModelList,
        (pageContext, mp) -> {
          mp.setTotalNoPages(pageContext.getTotalPages());
          mp.setAllPageNos(pageContext.getAllPageNos());
          mp.setCurrentPage(pageContext.getPageNo());
          mp.setTeamname(manageDatabase.getTeamNameFromTeamId(tenantId, mp.getTeamId()));
          return mp;
        });
  }

  private List<TopicSyncResponseModel> getSyncTopicListRecon(
      List<TopicConfig> clusterTopicsList,
      List<TopicSyncResponseModel> deletedTopicsFromClusterList,
      String env,
      boolean isBulkOption,
      int tenantId,
      boolean scheduledThread) {
    // Get Sync topics
    List<Topic> topicsFromSOT =
        manageDatabase.getHandleDbRequests().getSyncTopics(env, null, tenantId);

    //    topicsFromSOT = commonUtilsService.getFilteredTopicsForTenant(topicsFromSOT);
    List<TopicRequest> topicsListMap = new ArrayList<>();

    List<String> teamList;
    teamList = tenantFilterTeams(tenantId, scheduledThread);
    int counterInc;

    if (!isBulkOption) {
      updateClusterDeletedTopicsList(
          clusterTopicsList, deletedTopicsFromClusterList, topicsFromSOT, teamList, tenantId);
    }

    for (int i = 0; i < clusterTopicsList.size(); i++) {
      counterInc = counterIncrement();
      TopicRequest mp = new TopicRequest();
      if (createTopicRequest(
          clusterTopicsList, topicsFromSOT, teamList, i, counterInc, mp, tenantId)) {
        if (mp.getTeamId().equals(0) || mp.getTeamId() == null) {
          topicsListMap.add(mp);
        }
      }
    }

    // topics which exist in cluster and not in kw.
    List<TopicSyncResponseModel> topicSyncModelList =
        getTopicSyncModels(topicsListMap, env, tenantId);
    topicSyncModelList.addAll(deletedTopicsFromClusterList);

    return topicSyncModelList;
  }

  private List<TopicSyncResponseModel> getTopicSyncModels(
      List<TopicRequest> topicsList, String envId, Integer tenantId) {
    List<TopicSyncResponseModel> topicSyncList = new ArrayList<>();
    TopicSyncResponseModel topicSyncModel;

    Env env = getEnvDetails(envId, tenantId);

    String topicPrefix = "";
    String topicSuffix = "";
    Pattern topicRegex = null;
    int maxRepFactor = 0, maxPartitions = 0;
    if (env.getParams() != null) {
      topicPrefix = getValueOrDefault(env.getParams().getTopicPrefix(), "");
      topicSuffix = getValueOrDefault(env.getParams().getTopicSuffix(), "");
      topicRegex = Pattern.compile(getValueOrDefault(env.getParams().getTopicRegex(), ""));
      maxPartitions = Integer.parseInt(getValueOrDefault(env.getParams().getMaxPartitions(), "0"));
      maxRepFactor = Integer.parseInt(getValueOrDefault(env.getParams().getMaxRepFactor(), "0"));
    }

    for (TopicRequest topicReq : topicsList) {
      topicSyncModel = new TopicSyncResponseModel();
      copyProperties(topicReq, topicSyncModel);
      if (env.getParams() != null) {
        topicSyncModel.setValidationStatus(
            doesTopicConformToEnvValidation(
                topicSyncModel,
                topicPrefix,
                topicSuffix,
                topicRegex,
                env.getParams().isApplyRegex(),
                maxRepFactor,
                maxPartitions));
        topicSyncModel.setValidatedTopic(StringUtils.isEmpty(topicSyncModel.getValidationStatus()));
      } else {
        // no validation set so set boolean to true
        topicSyncModel.setValidatedTopic(true);
      }

      topicSyncList.add(topicSyncModel);
    }
    return topicSyncList;
  }

  private boolean createTopicRequest(
      List<TopicConfig> topicsList,
      List<Topic> topicsFromSOT,
      List<String> teamList,
      int i,
      int counterInc,
      TopicRequest mp,
      int tenantId) {
    TopicConfig topicMap;
    mp.setSequence(counterInc + "");

    topicMap = topicsList.get(i);
    final String tmpTopicName = topicMap.getTopicName();

    mp.setTopicname(tmpTopicName);
    mp.setTopicpartitions(Integer.parseInt(topicMap.getPartitions()));
    mp.setReplicationfactor(topicMap.getReplicationFactor());

    String teamUpdated = null;

    try {
      Optional<Topic> teamUpdatedFirst =
          topicsFromSOT.stream()
              .filter(a -> Objects.equals(a.getTopicname(), tmpTopicName))
              .findFirst();

      if (teamUpdatedFirst.isPresent()) {
        teamUpdated =
            manageDatabase.getTeamNameFromTeamId(tenantId, teamUpdatedFirst.get().getTeamId());
      }
    } catch (Exception e) {
      log.error("Error from getSyncTopicList ", e);
    }

    if (teamUpdated != null && !teamUpdated.equals("undefined")) {
      mp.setPossibleTeams(teamList);
      if (teamList.contains(teamUpdated)) {
        mp.setTeamId(manageDatabase.getTeamIdFromTeamName(tenantId, teamUpdated));
      } else {
        return false; // belongs to different tenant
      }
    } else {
      mp.setPossibleTeams(teamList);
      mp.setTeamId(0);
      mp.setRemarks("ADDED");
    }

    return true;
  }

  private void updateClusterDeletedTopicsList(
      List<TopicConfig> clusterTopicsList,
      List<TopicSyncResponseModel> deletedTopicsFromClusterList,
      List<Topic> topicsFromSOT,
      List<String> teamList,
      int tenantId) {
    try {
      List<String> clusterTopicStringList = new ArrayList<>();
      clusterTopicsList.forEach(
          hashMapTopicObj -> clusterTopicStringList.add(hashMapTopicObj.getTopicName()));

      Map<String, TopicSyncResponseModel> sotTopics = new HashMap<>();

      List<String> sotTopicStringList = new ArrayList<>();
      for (Topic topicObj : topicsFromSOT) {
        List<String> possibleTeams = new ArrayList<>();
        possibleTeams.add(manageDatabase.getTeamNameFromTeamId(tenantId, topicObj.getTeamId()));
        possibleTeams.add(SYNC_102);

        sotTopicStringList.add(topicObj.getTopicname());
        TopicSyncResponseModel topicSyncModel = new TopicSyncResponseModel();
        topicSyncModel.setTopicname(topicObj.getTopicname());
        topicSyncModel.setEnvironment(topicObj.getEnvironment());
        topicSyncModel.setTopicpartitions(topicObj.getNoOfPartitions());
        topicSyncModel.setReplicationfactor(topicObj.getNoOfReplicas());
        topicSyncModel.setTeamId(topicObj.getTeamId());
        topicSyncModel.setTeamname(
            manageDatabase.getTeamNameFromTeamId(tenantId, topicObj.getTeamId()));
        topicSyncModel.setPossibleTeams(possibleTeams);
        topicSyncModel.setSequence("" + topicObj.getTopicid());
        topicSyncModel.setRemarks("DELETED");

        // tenant teams
        if (teamList.contains(
            manageDatabase.getTeamNameFromTeamId(tenantId, topicObj.getTeamId()))) {
          sotTopics.put(topicObj.getTopicname(), topicSyncModel);
        }
      }

      List<String> deletedTopicsFromClusterListTmp =
          sotTopicStringList.stream()
              .filter(topicName -> !clusterTopicStringList.contains(topicName))
              .toList();
      deletedTopicsFromClusterListTmp.forEach(
          topicName -> deletedTopicsFromClusterList.add(sotTopics.get(topicName)));
    } catch (Exception e) {
      log.error("Error from updateClusterDeletedTopicsList ", e);
    }
  }

  private List<String> tenantFilterTeams(Integer tenantId, boolean scheduledThread) {
    if (!scheduledThread
        && (!commonUtilsService.isNotAuthorizedUser(
            commonUtilsService.getPrincipal(),
            Set.of(
                PermissionType.SYNC_BACK_SUBSCRIPTIONS,
                PermissionType.SYNC_TOPICS,
                PermissionType.SYNC_SUBSCRIPTIONS,
                PermissionType.SYNC_BACK_TOPICS)))) {
      // tenant filtering
      if (tenantId == null) {
        tenantId = commonUtilsService.getTenantId(getUserName());
      }
    }
    List<Team> teams = manageDatabase.getHandleDbRequests().getAllTeams(tenantId);
    List<String> teamListUpdated = new ArrayList<>();
    for (Team teamsItem : teams) {
      teamListUpdated.add(teamsItem.getTeamname());
    }

    return teamListUpdated;
  }

  public ApiResponse updateSyncBackTopics(SyncBackTopics syncBackTopics) {
    log.info("updateSyncBackTopics {}", syncBackTopics);
    Map<String, List<String>> resultMap = new HashMap<>();
    int tenantId = commonUtilsService.getTenantId(getUserName());

    List<String> logArray = new ArrayList<>();

    logArray.add(
        "Source Environment " + getEnvDetails(syncBackTopics.getSourceEnv(), tenantId).getName());
    logArray.add(
        "Target Environment " + getEnvDetails(syncBackTopics.getTargetEnv(), tenantId).getName());
    logArray.add("Type of Sync " + syncBackTopics.getTypeOfSync());

    if (commonUtilsService.isNotAuthorizedUser(
        commonUtilsService.getPrincipal(), PermissionType.SYNC_BACK_TOPICS)) {
      return ApiResponse.NOT_AUTHORIZED;
    }

    List<String> resultStatus = new ArrayList<>();
    resultStatus.add(ApiResultStatus.SUCCESS.value);
    resultMap.put("result", resultStatus);

    if ("SELECTED_TOPICS".equals(syncBackTopics.getTypeOfSync())) {
      for (String topicId : syncBackTopics.getTopicIds()) {
        Optional<Topic> topicFoundOptional =
            manageDatabase
                .getHandleDbRequests()
                .getTopicFromId(Integer.parseInt(topicId), tenantId);
        Topic topicFound;
        if (topicFoundOptional.isPresent()) {
          topicFound = topicFoundOptional.get();
          approveSyncBackTopics(syncBackTopics, resultMap, logArray, topicFound, tenantId);
        }
      }
    } else {
      List<Topic> topics =
          manageDatabase
              .getHandleDbRequests()
              .getTopicsFromEnv(syncBackTopics.getSourceEnv(), tenantId);
      for (Topic topicFound : topics) {
        approveSyncBackTopics(syncBackTopics, resultMap, logArray, topicFound, tenantId);
      }
    }

    String result = resultMap.get("result").get(0);
    return ApiResponse.builder()
        .success(result.equals(ApiResultStatus.SUCCESS.value))
        .message(result)
        .data(logArray)
        .build();
  }

  private void approveSyncBackTopics(
      SyncBackTopics syncBackTopics,
      Map<String, List<String>> resultMap,
      List<String> logUpdateSyncBackTopics,
      Topic topicFound,
      int tenantId) {
    try {
      ResponseEntity<ApiResponse> response =
          clusterApiService.approveTopicRequests(
              topicFound.getTopicname(),
              RequestOperationType.CREATE.value,
              topicFound.getNoOfPartitions(),
              topicFound.getNoOfReplicas(),
              syncBackTopics.getTargetEnv(),
              UtilMethods.createAdvancedConfigFromJson(topicFound.getJsonParams(), mapper),
              tenantId,
              false);

      if (!Objects.equals(
          Objects.requireNonNull(response.getBody()).getMessage(), ApiResultStatus.SUCCESS.value)) {
        log.error("Error in creating topic {} {}", topicFound, response.getBody());
        if (Objects.requireNonNull(response.getBody())
            .getMessage()
            .contains("TopicExistsException")) {
          logUpdateSyncBackTopics.add(
              TOPICS_SYNC_ERR_101
                  + topicFound.getTopicname()
                  + " already exists. TopicExistsException");
        } else {
          logUpdateSyncBackTopics.add(
              TOPICS_SYNC_ERR_101 + topicFound.getTopicname() + " " + response.getBody());
        }
      } else {
        logUpdateSyncBackTopics.add("Topic created " + topicFound.getTopicname());
        if (!Objects.equals(syncBackTopics.getSourceEnv(), syncBackTopics.getTargetEnv())) {
          createAndApproveTopicRequest(syncBackTopics, topicFound, tenantId);
        }
      }
    } catch (KlawException e) {
      log.error("Error in creating topic {}", topicFound, e);
      List<String> resultStatus = new ArrayList<>();
      resultStatus.add("Error :" + e.getMessage());
      resultMap.put("result", resultStatus);
    }
  }

  private void createAndApproveTopicRequest(
      SyncBackTopics syncBackTopics, Topic topicFound, int tenantId) {
    List<Topic> topics =
        commonUtilsService.getTopicsForTopicName(topicFound.getTopicname(), tenantId);
    Integer teamName;
    if (topics != null && topics.size() > 0) {
      teamName = topics.get(0).getTeamId();

      TopicRequest topicRequest;
      topicRequest = new TopicRequest();
      topicRequest.setRequestOperationType(RequestOperationType.CREATE.value);
      topicRequest.setTopicname(topicFound.getTopicname());
      topicRequest.setEnvironment(syncBackTopics.getTargetEnv());
      topicRequest.setTopicpartitions(topicFound.getNoOfPartitions());
      topicRequest.setReplicationfactor(topicFound.getNoOfReplicas());
      topicRequest.setRequestor(getUserName());
      topicRequest.setTeamId(teamName);
      topicRequest.setTenantId(tenantId);
      // Create request
      Map<String, String> createResult =
          manageDatabase.getHandleDbRequests().requestForTopic(topicRequest);
      // TODO performance improvement batch save these requests to the DB.
      // Approve request
      if (createResult.get("topicId") != null) {
        topicRequest.setTopicid(Integer.parseInt(createResult.get("topicId")));
        CRUDResponse<Topic> saveResults =
            manageDatabase.getHandleDbRequests().updateTopicRequest(topicRequest, getUserName());
        // entities size should always be equal to 1 as they are saved one at a time here.
        if (saveResults.getResultStatus().equals(ApiResultStatus.SUCCESS.value)
            && saveResults.getEntities().size() > 1) {
          manageDatabase.addTopicToCache(tenantId, saveResults.getEntities().get(0));
        }
      }
    }
    // Single reset on other servers.
    // Later this should be updated to send particular object across instead of having the cache
    // reloaded on the other side.

    commonUtilsService.resetCacheOnOtherServers(
        KwMetadataUpdates.builder()
            .tenantId(tenantId)
            .entityType(EntityType.TOPICS.name())
            .operationType(RequestOperationType.CREATE.value)
            .build());
  }

  public List<TopicInfo> getTopicsRowView(
      String env,
      String pageNo,
      String currentPage,
      String topicNameSearch,
      Integer teamId,
      String topicType) {
    log.info("getTopicsRowView {}", topicNameSearch);
    int tenantId = commonUtilsService.getTenantId(getUserName());
    List<TopicInfo> topicListUpdated =
        getTopicsPaginated(env, pageNo, currentPage, topicNameSearch, teamId, topicType, tenantId);

    if (topicListUpdated != null && topicListUpdated.size() > 0) {
      return topicListUpdated;
    }

    return new ArrayList<>();
  }

  private List<TopicInfo> getTopicsPaginated(
      String env,
      String pageNo,
      String currentPage,
      String topicNameSearch,
      Integer teamId,
      String topicType,
      int tenantId) {
    if (topicNameSearch != null) topicNameSearch = topicNameSearch.trim();

    HandleDbRequests handleDbRequests = manageDatabase.getHandleDbRequests();

    // To get Producer or Consumer topics, first get all topics based on acls and then filter
    List<Topic> producerConsumerTopics = new ArrayList<>();
    if ((AclType.PRODUCER.value.equals(topicType) || AclType.CONSUMER.value.equals(topicType))
        && teamId != 0) {
      producerConsumerTopics =
          handleDbRequests.getAllTopicsByTopictypeAndTeamnameAndEnv(
              topicType, teamId, tenantId, env);

      // tenant filtering, not really necessary though, as based on team is searched.
      //      producerConsumerTopics =
      //          commonUtilsService.getFilteredTopicsForTenant(producerConsumerTopics);

      // select all topics and then filter
      env = "ALL";
      teamId = 0;
    }

    // Get Sync topics
    List<Topic> topicsFromSOT = handleDbRequests.getSyncTopics(env, teamId, tenantId);
    //    topicsFromSOT = commonUtilsService.getFilteredTopicsForTenant(topicsFromSOT);

    // tenant filtering
    List<Env> listAllEnvs = manageDatabase.getKafkaEnvList(tenantId);
    String orderOfEnvs = commonUtilsService.getEnvProperty(tenantId, ORDER_OF_TOPIC_ENVS);

    topicsFromSOT = commonUtilsService.groupTopicsByEnv(topicsFromSOT);
    List<Topic> filterProducerConsumerList = new ArrayList<>();
    String tmpTopicFull, tmpTopicSub;

    if (producerConsumerTopics.size() > 0) {
      for (Topic topicInfo : topicsFromSOT) {
        for (Topic producerConsumerTopic : producerConsumerTopics) {
          tmpTopicFull = producerConsumerTopic.getTopicname();

          if (tmpTopicFull.endsWith("--PREFIXED--")) { // has prefixed acl
            tmpTopicSub = tmpTopicFull.replace("--PREFIXED--", "");
            if (topicInfo.getTopicname().startsWith(tmpTopicSub)
                && topicInfo
                    .getEnvironmentsSet()
                    .contains(producerConsumerTopic.getEnvironment())) {
              topicInfo.setEnvironmentsSet(producerConsumerTopic.getEnvironmentsSet());
              filterProducerConsumerList.add(topicInfo);
            }
          } else if (Objects.equals(producerConsumerTopic.getTopicname(), topicInfo.getTopicname())
              && topicInfo.getEnvironmentsSet().contains(producerConsumerTopic.getEnvironment())) {
            topicInfo.setEnvironmentsSet(producerConsumerTopic.getEnvironmentsSet());
            filterProducerConsumerList.add(topicInfo);
          }
        }
      }
      topicsFromSOT = filterProducerConsumerList;
    }

    List<Topic> topicFilteredList = topicsFromSOT;
    // Filter topics on topic name for search
    if (topicNameSearch != null && topicNameSearch.length() > 0) {
      final String topicSearchFilter = topicNameSearch;
      topicFilteredList =
          topicsFromSOT.stream()
              .filter(topic -> topic.getTopicname().contains(topicSearchFilter))
              .collect(Collectors.toList());

      // searching documentation
      List<Topic> searchDocList =
          topicsFromSOT.stream()
              .filter(
                  topic ->
                      (topic.getDocumentation() != null
                          && topic.getDocumentation().contains(topicSearchFilter)))
              .toList();

      topicFilteredList.addAll(searchDocList);
      topicFilteredList =
          new ArrayList<>(
              topicFilteredList.stream()
                  .collect(
                      Collectors.toConcurrentMap(
                          Topic::getTopicname, Function.identity(), (p, q) -> p))
                  .values());
    }

    topicsFromSOT =
        topicFilteredList.stream()
            .sorted(new TopicControllerService.TopicNameComparator())
            .collect(Collectors.toList());

    return topicsFromSOT.isEmpty()
        ? null
        : Pager.getItemsList(
            pageNo,
            currentPage,
            21,
            topicsFromSOT,
            (pageContext, topicSOT) -> {
              int counterInc = counterIncrement();
              TopicInfo mp = new TopicInfo();
              mp.setSequence(counterInc + "");

              TreeSet<String> envSet = new TreeSet<>(Comparator.comparingInt(orderOfEnvs::indexOf));
              envSet.addAll(topicSOT.getEnvironmentsSet());

              mp.setTopicid(topicSOT.getTopicid());
              mp.setEnvName(topicSOT.getEnvironment());
              mp.setEnvironmentsList(KlawResourceUtils.getConvertedEnvs(listAllEnvs, envSet));
              mp.setTopicName(topicSOT.getTopicname());
              mp.setTeamname(manageDatabase.getTeamNameFromTeamId(tenantId, topicSOT.getTeamId()));

              mp.setNoOfReplicas(topicSOT.getNoOfReplicas());
              mp.setNoOfPartitions(topicSOT.getNoOfPartitions());
              mp.setDescription(topicSOT.getDescription());

              mp.setTotalNoPages(pageContext.getTotalPages());
              mp.setCurrentPage(pageContext.getPageNo());
              mp.setAllPageNos(pageContext.getAllPageNos());
              return mp;
            });
  }

  private int counterIncrement() {
    topicCounter++;
    return topicCounter;
  }

  public ApiResponse updateSyncTopicsBulk(SyncTopicsBulk syncTopicsBulk) throws KlawException {
    log.info("updateSyncTopicsBulk {}", syncTopicsBulk);
    //    Map<String, List<String>> resultMap = new HashMap<>();
    int tenantId = commonUtilsService.getTenantId(getUserName());
    List<String> logArray = new ArrayList<>();

    logArray.add(
        "Source Environment " + getEnvDetails(syncTopicsBulk.getSourceEnv(), tenantId).getName());
    logArray.add("Assigned to Team " + syncTopicsBulk.getSelectedTeam());
    logArray.add("Type of Sync " + syncTopicsBulk.getTypeOfSync());

    if (commonUtilsService.isNotAuthorizedUser(
        commonUtilsService.getPrincipal(), PermissionType.SYNC_TOPICS)) {
      return ApiResponse.NOT_AUTHORIZED;
    }

    if ("SELECTED_TOPICS".equals(syncTopicsBulk.getTypeOfSync())) {
      Object[] topicMap = syncTopicsBulk.getTopicDetails();
      Map<String, Map<String, Object>> hashMap = new HashMap<>();
      Map<String, Object> subObj;
      for (Object o : topicMap) {
        subObj = (Map<String, Object>) o;
        hashMap.put((String) subObj.get("topicName"), subObj);
      }

      for (String topicName : syncTopicsBulk.getTopicNames()) {
        invokeUpdateSync(syncTopicsBulk, logArray, hashMap, topicName);
      }
    } else {
      try {
        LoadTopicsResponse loadTopicsResponse =
            getTopicsFromKafkaCluster(
                syncTopicsBulk.getSourceEnv(),
                syncTopicsBulk.getTopicSearchFilter(),
                false,
                tenantId);
        for (TopicConfig topicConfig : loadTopicsResponse.getTopicConfigSet()) {
          invokeUpdateSyncAllTopics(syncTopicsBulk, logArray, topicConfig);
        }
      } catch (Exception e) {
        log.error("Could not retrieve topics ", e);
        throw new KlawException(e.getMessage());
      }
    }

    return ApiResponse.builder()
        .success(true)
        .message(ApiResultStatus.SUCCESS.value)
        .data(logArray)
        .build();
  }

  private void invokeUpdateSyncAllTopics(
      SyncTopicsBulk syncTopicsBulk, List<String> logArray, TopicConfig hashMap) {
    SyncTopicUpdates syncTopicUpdates;
    List<SyncTopicUpdates> updatedSyncTopicsList = new ArrayList<>();

    syncTopicUpdates = new SyncTopicUpdates();
    syncTopicUpdates.setTeamSelected(syncTopicsBulk.getSelectedTeam());
    syncTopicUpdates.setTopicName(hashMap.getTopicName());
    syncTopicUpdates.setEnvSelected(syncTopicsBulk.getSourceEnv());
    syncTopicUpdates.setPartitions(Integer.parseInt(hashMap.getPartitions()));
    syncTopicUpdates.setReplicationFactor(hashMap.getReplicationFactor());

    updatedSyncTopicsList.add(syncTopicUpdates);
    try {
      logArray.add(
          "Topic status :"
              + hashMap.getTopicName()
              + " "
              + updateSyncTopics(updatedSyncTopicsList).getMessage());
    } catch (Exception e) {
      logArray.add(TOPICS_SYNC_ERR_102 + hashMap.getTopicName() + " " + e);
      log.error("Exception:", e);
    }
  }

  private LoadTopicsResponse getTopicsFromKafkaCluster(
      String env, String topicNameSearch, boolean resetTopicsCache, Integer tenantId)
      throws Exception {
    if (topicNameSearch != null) {
      topicNameSearch = topicNameSearch.trim();
    }
    Env envSelected = getEnvDetails(env, tenantId);
    KwClusters kwClusters =
        manageDatabase
            .getClusters(KafkaClustersType.KAFKA, tenantId)
            .get(envSelected.getClusterId());

    LoadTopicsResponse loadTopicsResponse =
        clusterApiService.getAllTopics(
            kwClusters.getBootstrapServers(),
            kwClusters.getProtocol(),
            kwClusters.getClusterName() + kwClusters.getClusterId(),
            kwClusters.getKafkaFlavor(),
            tenantId,
            resetTopicsCache);

    topicCounter = 0;

    Set<TopicConfig> topicFilteredList = loadTopicsResponse.getTopicConfigSet();
    // Filter topics on topic name for search

    if (topicNameSearch != null
        && topicNameSearch.length() > 0
        && !loadTopicsResponse.isLoadingInProgress()) {
      final String topicSearchFilter = topicNameSearch;
      topicFilteredList =
          loadTopicsResponse.getTopicConfigSet().stream()
              .filter(topic -> topic.getTopicName().contains(topicSearchFilter))
              .collect(Collectors.toSet());
    }

    return LoadTopicsResponse.builder()
        .topicConfigSet(topicFilteredList)
        .loadingInProgress(loadTopicsResponse.isLoadingInProgress())
        .build();
  }

  private void invokeUpdateSync(
      SyncTopicsBulk syncTopicsBulk,
      List<String> logArray,
      Map<String, Map<String, Object>> hashMap,
      String topicName) {
    SyncTopicUpdates syncTopicUpdates;
    List<SyncTopicUpdates> updatedSyncTopicsList = new ArrayList<>();

    syncTopicUpdates = new SyncTopicUpdates();
    syncTopicUpdates.setTeamSelected(syncTopicsBulk.getSelectedTeam());
    syncTopicUpdates.setTopicName(topicName);
    syncTopicUpdates.setEnvSelected(syncTopicsBulk.getSourceEnv());
    syncTopicUpdates.setPartitions((Integer) hashMap.get(topicName).get("topicPartitions"));
    syncTopicUpdates.setReplicationFactor(
        hashMap.get(topicName).get("topicReplicationFactor") + "");

    updatedSyncTopicsList.add(syncTopicUpdates);
    try {
      logArray.add(
          "Topic status :"
              + topicName
              + " "
              + updateSyncTopics(updatedSyncTopicsList).getMessage());
    } catch (Exception e) {
      log.error("Exception:", e);
      logArray.add(TOPICS_SYNC_ERR_102 + topicName + " " + e);
    }
  }

  public ApiResponse updateSyncTopics(List<SyncTopicUpdates> updatedSyncTopics)
      throws KlawException {
    log.info("updateSyncTopics {}", updatedSyncTopics);
    String userDetails = getUserName();

    if (commonUtilsService.isNotAuthorizedUser(
        commonUtilsService.getPrincipal(), PermissionType.SYNC_TOPICS)) {
      return ApiResponse.NOT_AUTHORIZED;
    }

    // tenant filtering
    int tenantId = commonUtilsService.getTenantId(getUserName());
    String syncCluster = manageDatabase.getTenantConfig().get(tenantId).getBaseSyncEnvironment();
    String orderOfEnvs = commonUtilsService.getEnvProperty(tenantId, ORDER_OF_TOPIC_ENVS);

    List<Topic> existingTopics;
    List<Topic> listTopics = new ArrayList<>();
    Topic t;

    StringBuilder erroredTopics = new StringBuilder();
    boolean topicsWithDiffTeams = false;

    StringBuilder erroredTopicsExist = new StringBuilder();
    boolean topicsDontExistInMainCluster = false;
    int topicId = manageDatabase.getHandleDbRequests().getNextTopicRequestId("TOPIC_ID", tenantId);

    // remove duplicates
    updatedSyncTopics =
        updatedSyncTopics.stream()
            .filter(
                topicUpdate ->
                    topicUpdate.getTeamSelected() != null
                        && !topicUpdate.getTeamSelected().equals(""))
            .collect(Collectors.toList());

    List<Integer> updatedSyncTopicsDelete = new ArrayList<>();
    updatedSyncTopics = handleTopicDeletes(updatedSyncTopics, updatedSyncTopicsDelete, tenantId);

    if (updatedSyncTopics.size() > 0) {
      for (SyncTopicUpdates topicUpdate : updatedSyncTopics) {
        // tenant filtering
        if (!commonUtilsService
            .getEnvsFromUserId(userDetails)
            .contains(topicUpdate.getEnvSelected())) {
          return ApiResponse.NOT_AUTHORIZED;
        }
        existingTopics =
            commonUtilsService.getTopicsForTopicName(topicUpdate.getTopicName(), tenantId);

        if (existingTopics != null) {
          for (Topic existingTopic : existingTopics) {
            if (existingTopic.getEnvironment().equals(syncCluster)) {
              if (!manageDatabase
                      .getTeamNameFromTeamId(tenantId, existingTopic.getTeamId())
                      .equals(topicUpdate.getTeamSelected())
                  && !topicUpdate.getEnvSelected().equals(syncCluster)) {
                erroredTopics.append(topicUpdate.getTopicName()).append(" ");
                topicsWithDiffTeams = true;
              }
              break;
            }
          }
        } else if (!Objects.equals(syncCluster, topicUpdate.getEnvSelected())) {
          erroredTopicsExist.append(topicUpdate.getTopicName()).append(" ");
          if (checkInPromotionOrder(topicUpdate.getEnvSelected(), orderOfEnvs)) {
            topicsDontExistInMainCluster = true;
          }
        }

        boolean topicAdded = false;
        if (existingTopics == null) {
          t = new Topic();

          topicId = topicId + 1;
          t.setTopicid(topicId);
          t.setTopicname(topicUpdate.getTopicName());
          t.setNoOfPartitions(topicUpdate.getPartitions());
          t.setNoOfReplicas(topicUpdate.getReplicationFactor());
          t.setEnvironment(topicUpdate.getEnvSelected());
          t.setTeamId(
              manageDatabase.getTeamIdFromTeamName(tenantId, topicUpdate.getTeamSelected()));
          t.setDescription("Topic description");
          t.setExistingTopic(false);
          t.setTenantId(tenantId);

          listTopics.add(t);
        } else {
          for (Topic existingTopic : existingTopics) {
            if (Objects.equals(existingTopic.getEnvironment(), topicUpdate.getEnvSelected())) {
              t = existingTopic;
              t.setTeamId(
                  manageDatabase.getTeamIdFromTeamName(tenantId, topicUpdate.getTeamSelected()));
              t.setTopicname(topicUpdate.getTopicName());
              t.setEnvironment(existingTopic.getEnvironment());
              t.setExistingTopic(true);
              t.setTenantId(tenantId);
              listTopics.add(t);
              topicAdded = true;
            } else if (!Objects.equals(
                manageDatabase.getTeamNameFromTeamId(tenantId, existingTopic.getTeamId()),
                topicUpdate.getTeamSelected())) {
              t = existingTopic;
              t.setTeamId(
                  manageDatabase.getTeamIdFromTeamName(tenantId, topicUpdate.getTeamSelected()));
              t.setTopicname(topicUpdate.getTopicName());
              t.setEnvironment(existingTopic.getEnvironment());
              t.setExistingTopic(true);
              t.setTenantId(tenantId);
              listTopics.add(t);
              topicAdded = true;
            }
          }
        }

        boolean envFound = false;
        if (existingTopics != null) {
          for (Topic existingTopic : existingTopics) {
            if (Objects.equals(existingTopic.getEnvironment(), topicUpdate.getEnvSelected())) {
              envFound = true;
              break;
            }
          }
          if (!envFound && !topicAdded) {
            t = new Topic();
            topicId = topicId + 1;
            t.setTopicid(topicId);
            t.setTopicname(topicUpdate.getTopicName());
            t.setNoOfPartitions(topicUpdate.getPartitions());
            t.setNoOfReplicas(topicUpdate.getReplicationFactor());
            t.setEnvironment(topicUpdate.getEnvSelected());
            t.setTeamId(
                manageDatabase.getTeamIdFromTeamName(tenantId, topicUpdate.getTeamSelected()));
            t.setDescription("Topic description");
            t.setExistingTopic(false);
            t.setTenantId(tenantId);

            listTopics.add(t);
          }
        }
      }
    }

    if (updatedSyncTopics.size() == 0 && updatedSyncTopicsDelete.size() > 0) {
      manageDatabase.loadTopicsForOneTenant(tenantId);
      return ApiResponse.SUCCESS;
    }

    if (topicsDontExistInMainCluster) {
      return ApiResponse.notOk(
          TOPICS_SYNC_ERR_103 + syncCluster + ". \n Topics : " + erroredTopicsExist);
    }

    if (topicsWithDiffTeams) {
      return ApiResponse.notOk(
          TOPICS_SYNC_ERR_104 + syncCluster + ". \n Topics : " + erroredTopics);
    }

    if (listTopics.size() > 0) {
      try {
        CRUDResponse<Topic> statusSync =
            manageDatabase.getHandleDbRequests().addToSynctopics(listTopics);
        manageDatabase.loadTopicsForOneTenant(tenantId);

        return ApiResponse.ok(statusSync.getResultStatus());
      } catch (Exception e) {
        log.error(e.getMessage());
        throw new KlawException(e.getMessage());
      }
    } else {
      return ApiResponse.notOk(SYNC_ERR_101);
    }
  }

  private List<SyncTopicUpdates> handleTopicDeletes(
      List<SyncTopicUpdates> updatedSyncTopics,
      List<Integer> updatedSyncTopicsDelete,
      int tenantId) {
    List<SyncTopicUpdates> updatedSyncTopicsUpdated = new ArrayList<>();
    for (SyncTopicUpdates updatedSyncTopic : updatedSyncTopics) {
      if (SYNC_102.equals(updatedSyncTopic.getTeamSelected())) {
        updatedSyncTopicsDelete.add(Integer.parseInt(updatedSyncTopic.getSequence()));
      } else {
        updatedSyncTopicsUpdated.add(updatedSyncTopic);
      }
    }

    // delete topic
    for (Integer topicId : updatedSyncTopicsDelete) {
      manageDatabase.getHandleDbRequests().deleteTopic(topicId, tenantId);
    }

    return updatedSyncTopicsUpdated;
  }

  private String getUserName() {
    return mailService.getUserName(commonUtilsService.getPrincipal());
  }

  public Env getEnvDetails(String envId, Integer tenantId) {
    Optional<Env> envFound =
        manageDatabase.getKafkaEnvList(tenantId).stream()
            .filter(env -> Objects.equals(env.getId(), envId))
            .findFirst();
    return envFound.orElse(null);
  }

  private boolean checkInPromotionOrder(String envId, String orderOfEnvs) {
    List<String> orderedEnv = Arrays.asList(orderOfEnvs.split(","));
    return orderedEnv.contains(envId);
  }

  private String doesTopicConformToEnvValidation(
      TopicSyncResponseModel topicRequestReq,
      String topicPrefix,
      String topicSuffix,
      Pattern topicRegex,
      boolean applyRegex,
      int maxRepFactor,
      int maxPartitions) {
    String validationResponse = "";

    if (!applyRegex) {
      if (topicPrefix != null
          && !topicPrefix.isBlank()
          && !topicRequestReq.getTopicname().startsWith(topicPrefix)) {
        validationResponse +=
            String.format(
                TOPICS_SYNC_ERR_105, "prefix", topicPrefix, topicRequestReq.getTopicname());
      }

      if (topicSuffix != null
          && !topicSuffix.isBlank()
          && !topicRequestReq.getTopicname().endsWith(topicSuffix)) {
        validationResponse +=
            String.format(
                TOPICS_SYNC_ERR_105, "suffix", topicSuffix, topicRequestReq.getTopicname());
      }
    } else {
      if (topicRegex != null && !isRegexAMatch(topicRequestReq, topicRegex)) {

        validationResponse +=
            String.format(TOPICS_SYNC_ERR_105, "regex", topicRegex, topicRequestReq.getTopicname());
      }
    }

    if (Integer.parseInt(getValueOrDefault(topicRequestReq.getReplicationfactor(), "0"))
        > maxRepFactor) {
      validationResponse +=
          String.format(TOPICS_SYNC_ERR_106, maxRepFactor, topicRequestReq.getReplicationfactor());
    }
    if (topicRequestReq.getTopicpartitions() != null
        && topicRequestReq.getTopicpartitions().intValue() > maxPartitions) {
      validationResponse +=
          String.format(
              TOPICS_SYNC_ERR_107, maxPartitions, topicRequestReq.getTopicpartitions().intValue());
    } else if (topicRequestReq.getTopicpartitions() == null) {
      validationResponse +=
          String.format(
              TOPICS_SYNC_ERR_108, maxPartitions, topicRequestReq.getTopicpartitions().intValue());
    }

    return validationResponse;
  }

  private static String getValueOrDefault(List<String> params, String defaultValue) {
    return (params != null && params.size() > 0) ? params.get(0) : defaultValue;
  }

  private static String getValueOrDefault(String param, String defaultValue) {
    return (param != null) ? param : defaultValue;
  }

  private boolean isRegexAMatch(TopicSyncResponseModel topicRequestReq, Pattern topicRegex) {
    Matcher m = topicRegex.matcher(topicRequestReq.getTopicname());
    return m.matches();
  }
}
