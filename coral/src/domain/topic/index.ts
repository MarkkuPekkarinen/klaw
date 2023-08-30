import {
  requestTopicClaim,
  requestTopicDeletion,
  requestTopicEdit,
  getTopicDetailsPerEnv,
  getTopicNames,
  getTopicRequests,
  getTopicTeam,
  getTopics,
  requestTopicPromotion,
  updateTopicDocumentation,
} from "src/domain/topic/topic-api";
import {
  DeleteTopicPayload,
  Topic,
  TopicDetailsPerEnv,
  TopicDocumentationMarkdown,
  TopicNames,
  TopicOverview,
  TopicRequest,
  TopicRequestOperationTypes,
  TopicRequestStatus,
  TopicSchemaOverview,
  TopicTeam,
} from "src/domain/topic/topic-types";
import { PromotionStatus } from "src/domain/promotion/promotion-types";
export {
  requestTopicClaim,
  requestTopicDeletion,
  requestTopicEdit,
  getTopicDetailsPerEnv,
  getTopicNames,
  getTopicRequests,
  getTopicTeam,
  getTopics,
  requestTopicPromotion,
  updateTopicDocumentation,
};
export type {
  DeleteTopicPayload,
  Topic,
  TopicDetailsPerEnv,
  TopicDocumentationMarkdown,
  TopicNames,
  TopicOverview,
  TopicRequest,
  TopicRequestOperationTypes,
  TopicRequestStatus,
  TopicSchemaOverview,
  TopicTeam,
  PromotionStatus,
};
