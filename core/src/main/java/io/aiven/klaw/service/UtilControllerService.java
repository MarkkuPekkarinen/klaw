package io.aiven.klaw.service;

import static io.aiven.klaw.error.KlawErrorMessages.*;
import static io.aiven.klaw.helpers.KwConstants.APPROVER_SUBSCRIPTIONS;
import static io.aiven.klaw.helpers.KwConstants.CORAL_INDEX_FILE_PATH;
import static io.aiven.klaw.helpers.KwConstants.KLAW_OPTIONAL_PERMISSION_NEW_TOPIC_CREATION_KEY;
import static io.aiven.klaw.helpers.KwConstants.REQUESTOR_SUBSCRIPTIONS;
import static io.aiven.klaw.model.enums.AuthenticationType.ACTIVE_DIRECTORY;
import static io.aiven.klaw.model.enums.RolesType.SUPERADMIN;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aiven.klaw.config.ManageDatabase;
import io.aiven.klaw.dao.AclRequests;
import io.aiven.klaw.dao.KafkaConnectorRequest;
import io.aiven.klaw.dao.SchemaRequest;
import io.aiven.klaw.dao.TopicRequest;
import io.aiven.klaw.dao.UserInfo;
import io.aiven.klaw.helpers.HandleDbRequests;
import io.aiven.klaw.helpers.KwConstants;
import io.aiven.klaw.model.ApiResponse;
import io.aiven.klaw.model.KwMetadataUpdates;
import io.aiven.klaw.model.enums.ApiResultStatus;
import io.aiven.klaw.model.enums.EntityType;
import io.aiven.klaw.model.enums.PermissionType;
import io.aiven.klaw.model.enums.RequestStatus;
import io.aiven.klaw.model.requests.ResetEntityCache;
import io.aiven.klaw.model.response.AuthenticationInfo;
import io.aiven.klaw.model.response.DashboardStats;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ConfigurationProperties(prefix = "spring.security.oauth2.client", ignoreInvalidFields = false)
public class UtilControllerService implements InitializingBean {

  public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String GOOGLE_FEEDBACK_FORM_LINK = "https://forms.gle/F2Pi8aanWeJPDPLT8";
  public static final String ANONYMOUS_USER = "anonymousUser";
  public static final String IMAGE_URI = ".imageURI";
  @Autowired ManageDatabase manageDatabase;

  @Autowired MailUtils mailService;

  @Autowired private CommonUtilsService commonUtilsService;

  @Autowired BuildProperties buildProperties;

  @Value("${klaw.login.authentication.type}")
  private String authenticationType;

  @Value("${klaw.enable.authorization.ad:false}")
  private String adAuthRoleEnabled;

  @Value("${server.servlet.context-path:}")
  private String kwContextPath;

  @Value("${klaw.coral.enabled:false}")
  private boolean coralEnabled;

  private Map<String, String> registration;

  @Autowired private ConfigurableApplicationContext context;

  public static Boolean isCoralBuilt;

  @Override
  public void afterPropertiesSet() throws Exception {
    isCoralBuilt();
  }

  private void isCoralBuilt() {
    ClassPathResource file =
        new ClassPathResource(CORAL_INDEX_FILE_PATH, this.getClass().getClassLoader());
    isCoralBuilt = file.exists();
    log.info("Coral UI is Built == {}", isCoralBuilt);
  }

  public DashboardStats getDashboardStats() {
    log.debug("getDashboardInfo");
    String userName = getUserName();
    HandleDbRequests reqsHandle = manageDatabase.getHandleDbRequests();
    if (userName != null) {
      return reqsHandle.getDashboardStats(
          commonUtilsService.getTeamId(userName), commonUtilsService.getTenantId(getUserName()));
    }

    return new DashboardStats();
  }

  private String getUserName() {
    return mailService.getUserName(commonUtilsService.getPrincipal());
  }

  public String getTenantNameFromUser(String userId, UserInfo userInfo) {
    if (userInfo != null) {
      return manageDatabase.getTenantMap().entrySet().stream()
          .filter(obj -> Objects.equals(obj.getKey(), userInfo.getTenantId()))
          .findFirst()
          .get()
          .getValue();

    } else {
      return null;
    }
  }

  public Map<String, String> getAllRequestsToBeApproved(String requestor, int tenantId) {
    log.debug("getAllRequestsToBeApproved {}", requestor);
    HandleDbRequests reqsHandle = manageDatabase.getHandleDbRequests();

    Map<String, String> countList = new HashMap<>();
    String roleToSet = "";
    if (!commonUtilsService.isNotAuthorizedUser(
        commonUtilsService.getPrincipal(), PermissionType.APPROVE_SUBSCRIPTIONS)) {
      roleToSet = APPROVER_SUBSCRIPTIONS;
    }
    if (!commonUtilsService.isNotAuthorizedUser(
        commonUtilsService.getPrincipal(), PermissionType.REQUEST_CREATE_SUBSCRIPTIONS)) {
      roleToSet = REQUESTOR_SUBSCRIPTIONS;
    }
    List<SchemaRequest> allSchemaReqs =
        reqsHandle.getAllSchemaRequests(
            true,
            requestor,
            tenantId,
            null,
            null,
            null,
            null,
            null,
            !commonUtilsService.isNotAuthorizedUser(
                commonUtilsService.getPrincipal(), PermissionType.APPROVE_ALL_REQUESTS_TEAMS),
            false);

    List<AclRequests> allAclReqs;
    List<TopicRequest> allTopicReqs;
    List<KafkaConnectorRequest> allConnectorReqs;

    if (commonUtilsService.isNotAuthorizedUser(
        commonUtilsService.getPrincipal(), PermissionType.APPROVE_ALL_REQUESTS_TEAMS)) {
      allAclReqs =
          reqsHandle.getAllAclRequests(
              true,
              requestor,
              roleToSet,
              RequestStatus.CREATED.value,
              false,
              null,
              null,
              null,
              null,
              null,
              false,
              tenantId);
      allTopicReqs =
          reqsHandle.getCreatedTopicRequests(
              requestor, RequestStatus.CREATED.value, false, tenantId);
      allConnectorReqs =
          reqsHandle.getCreatedConnectorRequests(
              requestor, RequestStatus.CREATED.value, false, tenantId, null, null, null);
    } else {
      allAclReqs =
          reqsHandle.getAllAclRequests(
              true,
              requestor,
              roleToSet,
              RequestStatus.CREATED.value,
              true,
              null,
              null,
              null,
              null,
              null,
              false,
              tenantId);
      allTopicReqs =
          reqsHandle.getCreatedTopicRequests(
              requestor, RequestStatus.CREATED.value, true, tenantId);
      allConnectorReqs =
          reqsHandle.getCreatedConnectorRequests(
              requestor, RequestStatus.CREATED.value, true, tenantId, null, null, null);
    }

    try {
      // tenant filtering
      final Set<String> allowedEnvIdSet = commonUtilsService.getEnvsFromUserId(getUserName());
      allAclReqs =
          allAclReqs.stream()
              .filter(request -> allowedEnvIdSet.contains(request.getEnvironment()))
              .collect(Collectors.toList());

      // tenant filtering
      allSchemaReqs =
          allSchemaReqs.stream()
              .filter(request -> allowedEnvIdSet.contains(request.getEnvironment()))
              .collect(Collectors.toList());

      // tenant filtering
      allTopicReqs =
          allTopicReqs.stream()
              .filter(request -> allowedEnvIdSet.contains(request.getEnvironment()))
              .collect(Collectors.toList());

      // tenant filtering
      allConnectorReqs =
          allConnectorReqs.stream()
              .filter(request -> allowedEnvIdSet.contains(request.getEnvironment()))
              .collect(Collectors.toList());
    } catch (Exception e) {
      log.error("No environments/clusters found.", e);
      allAclReqs = new ArrayList<>();
      allSchemaReqs = new ArrayList<>();
      allTopicReqs = new ArrayList<>();
      allConnectorReqs = new ArrayList<>();
    }

    countList.put("topics", allTopicReqs.size() + "");
    countList.put("acls", allAclReqs.size() + "");
    countList.put("schemas", allSchemaReqs.size() + "");
    countList.put("connectors", allConnectorReqs.size() + "");

    if (commonUtilsService.isNotAuthorizedUser(
        commonUtilsService.getPrincipal(), PermissionType.ADD_EDIT_DELETE_USERS)) {
      countList.put("users", "0");
    } else {
      countList.put("users", reqsHandle.getCountRegisterUsersInfoForTenant(tenantId) + "");
    }

    return countList;
  }

  public AuthenticationInfo getAuth() {
    int tenantId = commonUtilsService.getTenantId(getUserName());
    String userName = getUserName();
    HandleDbRequests reqsHandle = manageDatabase.getHandleDbRequests();
    if (userName != null) {
      String teamName =
          manageDatabase.getTeamNameFromTeamId(tenantId, commonUtilsService.getTeamId(userName));
      String authority = commonUtilsService.getAuthority(commonUtilsService.getPrincipal());
      Map<String, String> outstanding = getAllRequestsToBeApproved(userName, tenantId);

      String outstandingTopicReqs = outstanding.get("topics");
      int outstandingTopicReqsInt = Integer.parseInt(outstandingTopicReqs);

      String outstandingAclReqs = outstanding.get("acls");
      int outstandingAclReqsInt = Integer.parseInt(outstandingAclReqs);

      String outstandingSchemasReqs = outstanding.get("schemas");
      int outstandingSchemasReqsInt = Integer.parseInt(outstandingSchemasReqs);

      String outstandingConnectorReqs = outstanding.get("connectors");
      int outstandingConnectorReqsInt = Integer.parseInt(outstandingConnectorReqs);

      String outstandingUserReqs = outstanding.get("users");
      int outstandingUserReqsInt = Integer.parseInt(outstandingUserReqs);

      if (outstandingTopicReqsInt <= 0) {
        outstandingTopicReqs = "0";
      }

      if (outstandingAclReqsInt <= 0) {
        outstandingAclReqs = "0";
      }

      if (outstandingSchemasReqsInt <= 0) {
        outstandingSchemasReqs = "0";
      }

      if (outstandingConnectorReqsInt <= 0) {
        outstandingConnectorReqs = "0";
      }

      if (outstandingUserReqsInt <= 0) {
        outstandingUserReqs = "0";
      }

      AuthenticationInfo authenticationInfo = new AuthenticationInfo();

      boolean isUserSuperadmin =
          SUPERADMIN
              .name()
              .equals(manageDatabase.getHandleDbRequests().getUsersInfo(userName).getRole());

      Map<String, String> teamTopics =
          reqsHandle.getDashboardInfo(commonUtilsService.getTeamId(userName), tenantId);
      authenticationInfo.setMyteamtopics(teamTopics.get("myteamtopics"));
      authenticationInfo.setMyOrgTopics(teamTopics.get("myOrgTopics"));
      authenticationInfo.setContextPath(kwContextPath);
      authenticationInfo.setTeamsize("" + manageDatabase.getTeamsForTenant(tenantId).size());
      authenticationInfo.setSchema_clusters_count(
          "" + manageDatabase.getSchemaRegEnvList(tenantId).size());
      authenticationInfo.setKafka_clusters_count(
          "" + manageDatabase.getKafkaEnvList(tenantId).size());
      authenticationInfo.setKafkaconnect_clusters_count(
          "" + manageDatabase.getKafkaConnectEnvList(tenantId).size());

      final Set<String> permissions =
          commonUtilsService.getPermissions(commonUtilsService.getPrincipal());
      final String canUpdatePermissions =
          getPermission(permissions, PermissionType.UPDATE_PERMISSIONS);
      final String addEditRoles = getPermission(permissions, PermissionType.ADD_EDIT_DELETE_ROLES);
      final String canShutdownKw = getPermission(permissions, PermissionType.SHUTDOWN_KLAW);
      final String syncBackTopics = getPermission(permissions, PermissionType.SYNC_BACK_TOPICS);
      final String syncBackAcls =
          getPermission(permissions, PermissionType.SYNC_BACK_SUBSCRIPTIONS);
      final String addUser = getPermission(permissions, PermissionType.ADD_EDIT_DELETE_USERS);
      final String addTeams = getPermission(permissions, PermissionType.ADD_EDIT_DELETE_TEAMS);
      final String viewKafkaConnect = getPermission(permissions, PermissionType.VIEW_CONNECTORS);
      final String requestTopics = getPermission(permissions, PermissionType.REQUEST_CREATE_TOPICS);
      final String requestAcls =
          getPermission(permissions, PermissionType.REQUEST_CREATE_SUBSCRIPTIONS);
      final String requestSchemas =
          getPermission(permissions, PermissionType.REQUEST_CREATE_SCHEMAS);
      final String requestConnector =
          getPermission(permissions, PermissionType.REQUEST_CREATE_CONNECTORS);

      String requestItems;
      if (ApiResultStatus.AUTHORIZED.value.equals(requestTopics)
          || ApiResultStatus.AUTHORIZED.value.equals(requestAcls)
          || ApiResultStatus.AUTHORIZED.value.equals(requestSchemas)
          || ApiResultStatus.AUTHORIZED.value.equals(requestConnector)) {
        requestItems = ApiResultStatus.AUTHORIZED.value;
      } else {
        requestItems = "NotAuthorized";
      }

      final String approveDeclineTopics = getPermission(permissions, PermissionType.APPROVE_TOPICS);
      final String approveDeclineSubscriptions =
          getPermission(permissions, PermissionType.APPROVE_SUBSCRIPTIONS);
      final String approveDeclineSchemas =
          getPermission(permissions, PermissionType.APPROVE_SCHEMAS);
      final String approveDeclineConnectors =
          getPermission(permissions, PermissionType.APPROVE_CONNECTORS);
      final String approveDeclineOperationalReqs =
          getPermission(permissions, PermissionType.APPROVE_OPERATIONAL_CHANGES);

      String approveAtleastOneRequest = "NotAuthorized";

      if (ApiResultStatus.AUTHORIZED.value.equals(approveDeclineTopics)
          || ApiResultStatus.AUTHORIZED.value.equals(approveDeclineSubscriptions)
          || ApiResultStatus.AUTHORIZED.value.equals(approveDeclineSchemas)
          || ApiResultStatus.AUTHORIZED.value.equals(approveDeclineConnectors)
          || ApiResultStatus.AUTHORIZED.value.equals(addUser)) {
        approveAtleastOneRequest = ApiResultStatus.AUTHORIZED.value;
      }

      String redirectionPage = "";
      if (ApiResultStatus.AUTHORIZED.value.equals(approveAtleastOneRequest)) {
        if (outstandingTopicReqsInt > 0
            && ApiResultStatus.AUTHORIZED.value.equals(approveDeclineTopics)) {
          redirectionPage = "execTopics";
        } else if (outstandingAclReqsInt > 0
            && ApiResultStatus.AUTHORIZED.value.equals(approveDeclineSubscriptions)) {
          redirectionPage = "execAcls";
        } else if (outstandingSchemasReqsInt > 0
            && ApiResultStatus.AUTHORIZED.value.equals(approveDeclineSchemas)) {
          redirectionPage = "execSchemas";
        } else if (outstandingConnectorReqsInt > 0
            && ApiResultStatus.AUTHORIZED.value.equals(approveDeclineConnectors)) {
          redirectionPage = "execConnectors";
        } else if (outstandingUserReqsInt > 0 && ApiResultStatus.AUTHORIZED.value.equals(addUser)) {
          redirectionPage = "execUsers";
        }
      }

      String syncTopicsAcls;

      if (commonUtilsService.isNotAuthorizedUser(userName, PermissionType.SYNC_TOPICS)
          || commonUtilsService.isNotAuthorizedUser(userName, PermissionType.SYNC_SUBSCRIPTIONS)) {
        syncTopicsAcls = "NotAuthorized";
      } else {
        syncTopicsAcls = ApiResultStatus.AUTHORIZED.value;
      }

      final String syncConnectors = getPermission(permissions, PermissionType.SYNC_CONNECTORS);
      final String manageConnectors = getPermission(permissions, PermissionType.MANAGE_CONNECTORS);
      final String syncSchemas = getPermission(permissions, PermissionType.SYNC_SCHEMAS);
      final String syncBackSchemas = getPermission(permissions, PermissionType.SYNC_BACK_SCHEMAS);

      String addDeleteEditTenants;
      String showServerConfigEnvProperties;

      final String updateServerConfig =
          getPermission(permissions, PermissionType.UPDATE_SERVERCONFIG);

      if (tenantId == KwConstants.DEFAULT_TENANT_ID
          && !commonUtilsService.isNotAuthorizedUser(
              userName, PermissionType.UPDATE_SERVERCONFIG)) {
        showServerConfigEnvProperties = ApiResultStatus.AUTHORIZED.value;
      } else {
        showServerConfigEnvProperties = "NotAuthorized";
      }

      if (tenantId == KwConstants.DEFAULT_TENANT_ID
          && isUserSuperadmin) { // allow adding tenants only to "default"
        addDeleteEditTenants = ApiResultStatus.AUTHORIZED.value;
      } else {
        addDeleteEditTenants = "NotAuthorized";
      }

      final String addDeleteEditEnvs =
          getPermission(permissions, PermissionType.ADD_EDIT_DELETE_ENVS);
      final String addDeleteEditClusters =
          getPermission(permissions, PermissionType.ADD_EDIT_DELETE_CLUSTERS);
      final String viewTopics = getPermission(permissions, PermissionType.VIEW_TOPICS);

      authenticationInfo.setAdAuthRoleEnabled(
          ""
              + (ACTIVE_DIRECTORY.value.equals(authenticationType)
                  && "true".equals(adAuthRoleEnabled)));

      String companyInfo = manageDatabase.getTenantFullConfig(tenantId).getOrgName();
      if (companyInfo == null || companyInfo.isEmpty()) {
        companyInfo = ENV_CLUSTER_TNT_109;
      }

      authenticationInfo.setSupportlink("https://github.com/aiven/klaw/issues");

      // broadcast text
      String broadCastText = "";
      String broadCastTextLocal =
          manageDatabase.getKwPropertyValue(KwConstants.broadCastTextProperty, tenantId);
      String broadCastTextGlobal =
          manageDatabase.getKwPropertyValue(
              KwConstants.broadCastTextProperty, KwConstants.DEFAULT_TENANT_ID);

      if (broadCastTextLocal != null && !broadCastTextLocal.isEmpty()) {
        broadCastText = "Announcement : " + broadCastTextLocal;
      }
      if (broadCastTextGlobal != null
          && !broadCastTextGlobal.isEmpty()
          && tenantId != KwConstants.DEFAULT_TENANT_ID) {
        broadCastText += " Announcement : " + broadCastTextGlobal;
      }

      String isOptionalExtraPermissionForTopicCreateEnabled =
          manageDatabase.getKwPropertyValue(
              KLAW_OPTIONAL_PERMISSION_NEW_TOPIC_CREATION_KEY, tenantId);

      authenticationInfo.setKlawOptionalPermissionNewTopicCreationEnabled(
          isOptionalExtraPermissionForTopicCreateEnabled);

      if (commonUtilsService.isNotAuthorizedUser(
          commonUtilsService.getPrincipal(), PermissionType.APPROVE_TOPICS_CREATE)) {
        authenticationInfo.setKlawOptionalPermissionNewTopicCreation("false");
      } else {
        authenticationInfo.setKlawOptionalPermissionNewTopicCreation("true");
      }

      UserInfo userInfo = manageDatabase.getHandleDbRequests().getUsersInfo(userName);
      authenticationInfo.setCanSwitchTeams("" + userInfo.isSwitchTeams());

      authenticationInfo.setBroadcastText(broadCastText);
      authenticationInfo.setTenantActiveStatus(
          manageDatabase.getTenantFullConfig(tenantId).getIsActive());
      authenticationInfo.setUsername(userName);
      authenticationInfo.setAuthenticationType(authenticationType);
      authenticationInfo.setTeamname(teamName);
      authenticationInfo.setTeamId("" + commonUtilsService.getTeamId(userName));
      authenticationInfo.setTenantName(getTenantNameFromUser(userName, userInfo));
      authenticationInfo.setUserrole(authority);
      authenticationInfo.setCompanyinfo(companyInfo);
      authenticationInfo.setKlawversion(buildProperties.getVersion());
      authenticationInfo.setNotifications(outstandingTopicReqs);
      authenticationInfo.setNotificationsAcls(outstandingAclReqs);
      authenticationInfo.setNotificationsSchemas(outstandingSchemasReqs);
      authenticationInfo.setNotificationsUsers(outstandingUserReqs);
      authenticationInfo.setNotificationsConnectors(outstandingConnectorReqs);
      authenticationInfo.setCanShutdownKw(canShutdownKw);
      authenticationInfo.setCanUpdatePermissions(canUpdatePermissions);
      authenticationInfo.setAddEditRoles(addEditRoles);
      authenticationInfo.setViewTopics(viewTopics);
      authenticationInfo.setRequestItems(requestItems);
      authenticationInfo.setViewKafkaConnect(viewKafkaConnect);
      authenticationInfo.setSyncBackTopics(syncBackTopics);
      authenticationInfo.setSyncBackSchemas(syncBackSchemas);
      authenticationInfo.setSyncBackAcls(syncBackAcls);
      authenticationInfo.setUpdateServerConfig(updateServerConfig);
      authenticationInfo.setShowServerConfigEnvProperties(showServerConfigEnvProperties);
      authenticationInfo.setAddUser(addUser);
      authenticationInfo.setAddTeams(addTeams);
      authenticationInfo.setSyncTopicsAcls(syncTopicsAcls);
      authenticationInfo.setSyncConnectors(syncConnectors);
      authenticationInfo.setManageConnectors(manageConnectors);
      authenticationInfo.setSyncSchemas(syncSchemas);
      authenticationInfo.setApproveAtleastOneRequest(approveAtleastOneRequest);
      authenticationInfo.setApproveDeclineTopics(approveDeclineTopics);
      authenticationInfo.setApproveDeclineOperationalReqs(approveDeclineOperationalReqs);
      authenticationInfo.setApproveDeclineSubscriptions(approveDeclineSubscriptions);
      authenticationInfo.setApproveDeclineSchemas(approveDeclineSchemas);
      authenticationInfo.setApproveDeclineConnectors(approveDeclineConnectors);
      authenticationInfo.setPendingApprovalsRedirectionPage(redirectionPage);
      authenticationInfo.setShowAddDeleteTenants(addDeleteEditTenants);
      authenticationInfo.setAddDeleteEditClusters(addDeleteEditClusters);
      authenticationInfo.setAddDeleteEditEnvs(addDeleteEditEnvs);
      authenticationInfo.setGoogleFeedbackFormLink(GOOGLE_FEEDBACK_FORM_LINK);

      // coral attributes
      authenticationInfo.setCoralEnabled(Boolean.toString(coralEnabled && isCoralBuilt));

      authenticationInfo.setCoralAvailableForUser(Boolean.toString(coralEnabled && isCoralBuilt));

      return authenticationInfo;
    } else return null;
  }

  private static String getPermission(Set<String> permissions, PermissionType permissionType) {
    String canUpdatePermissions;
    if (permissions.contains(permissionType.name())) {
      canUpdatePermissions = ApiResultStatus.AUTHORIZED.value;
    } else {
      canUpdatePermissions = "NotAuthorized";
    }
    return canUpdatePermissions;
  }

  public void getLogoutPage(HttpServletRequest request, HttpServletResponse response) {
    Authentication authentication = commonUtilsService.getAuthentication();
    if (authentication != null) {
      new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
  }

  public void shutdownContext() {
    int tenantId = commonUtilsService.getTenantId(getUserName());
    if (tenantId != KwConstants.DEFAULT_TENANT_ID) {
      return;
    }

    if (!commonUtilsService.isNotAuthorizedUser(
        commonUtilsService.getPrincipal(), PermissionType.SHUTDOWN_KLAW)) {
      log.info("Klaw Shutdown requested by {}", getUserName());
      context.close();
    }
  }

  public Map<String, Object> getBasicInfo() {
    Map<String, Object> resultBasicInfo = new HashMap<>();
    resultBasicInfo.put("contextPath", kwContextPath);

    resultBasicInfo.put("ssoProviders", buildSSOProviderDetails());

    // error codes of active directory authorization errors
    resultBasicInfo.put(ACTIVE_DIRECTORY_ERR_CODE_101, AD_ERROR_101_NO_MATCHING_ROLE);
    resultBasicInfo.put(ACTIVE_DIRECTORY_ERR_CODE_102, AD_ERROR_102_NO_MATCHING_TEAM);
    resultBasicInfo.put(ACTIVE_DIRECTORY_ERR_CODE_103, AD_ERROR_103_MULTIPLE_MATCHING_ROLE);
    resultBasicInfo.put(ACTIVE_DIRECTORY_ERR_CODE_104, AD_ERROR_104_MULTIPLE_MATCHING_TEAM);

    return resultBasicInfo;
  }

  /**
   * This method takes all configured provider information and creates a new totally seperate object
   * which just has the provider name and imgurl. This prevents any security information leaking
   * back to the user that could risk the security of Klaw.
   *
   * @return Sanitised Provider and an Image Url.
   */
  private Map<String, String> buildSSOProviderDetails() {
    Map<String, String> ssoProviders = new HashMap<>();
    if (registration != null) {
      registration
          .keySet()
          .forEach(
              k -> {
                String providerName = k.substring(0, k.indexOf("."));
                String imageUri = registration.get(providerName + IMAGE_URI);
                ssoProviders.put(providerName, imageUri == null ? "" : imageUri);
              });
    }

    return ssoProviders;
  }

  public ApiResponse resetCache(ResetEntityCache resetEntityCache) {
    String entityType = resetEntityCache.getEntityType();
    KwMetadataUpdates kwMetadataUpdates =
        KwMetadataUpdates.builder()
            .tenantId(resetEntityCache.getTenantId())
            .entityType(resetEntityCache.getEntityType())
            .entityValue(resetEntityCache.getEntityValue())
            .operationType(resetEntityCache.getOperationType())
            .createdTime(new Timestamp(System.currentTimeMillis()))
            .build();
    if (getUserName().equals(ANONYMOUS_USER)) {
      // continue with the request, ANONYMOUS_USER is generated by spring to access protected
      // resources
    } else if (entityType.equals(EntityType.USERS.name())
        && commonUtilsService.isNotAuthorizedUser(
            commonUtilsService.getPrincipal(), PermissionType.ADD_EDIT_DELETE_USERS)) {
      return ApiResponse.NOT_AUTHORIZED;
    }
    log.debug("Reset cache triggered on the instance {}", resetEntityCache);

    try {
      CompletableFuture.runAsync(
              () -> {
                commonUtilsService.updateMetadataCache(kwMetadataUpdates, false);
              })
          .get();
      return ApiResponse.SUCCESS;
    } catch (InterruptedException | ExecutionException e) {
      log.error("Error from resetCache ", e);
      return ApiResponse.notOk(e.getMessage());
    }
  }

  public Map<String, String> getRegistration() {
    return registration;
  }

  public void setRegistration(Map<String, String> registration) {
    this.registration = registration;
  }
}
