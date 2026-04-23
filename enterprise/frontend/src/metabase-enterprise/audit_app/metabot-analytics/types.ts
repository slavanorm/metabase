import type { MetabotChatMessage } from "metabase/metabot/state";
import type { DatasetQuery } from "metabase-types/api";

export type MetabotUserInfo = {
  id: number;
  email?: string;
  first_name?: string | null;
  last_name?: string | null;
};

export type ConversationSummary = {
  conversation_id: string;
  created_at: string;
  user_id: number;
  summary: string | null;
  message_count: number;
  user_message_count: number;
  assistant_message_count: number;
  total_tokens: number;
  last_message_at: string | null;
  model: string | null;
  search_count: number;
  query_count: number;
  ip_address: string | null;
  user: MetabotUserInfo | null;
};

export type ConversationSortColumn =
  | "created_at"
  | "message_count"
  | "total_tokens";

export type ConversationsRequest = {
  limit?: number;
  offset?: number;
  "user-id"?: number;
  "sort-by"?: ConversationSortColumn;
  "sort-dir"?: "asc" | "desc";
};

export type ConversationsResponse = {
  data: ConversationSummary[];
  total: number;
  limit: number;
  offset: number;
};

export type GeneratedQuery = {
  tool: string;
  call_id: string | null;
  message_id: number;
  query_id: string | null;
  query_type: "sql" | "notebook";
  sql: string | null;
  mbql: DatasetQuery | null;
  database_id: number | null;
  tables: string[];
};

export type ConversationDetail = {
  conversation_id: string;
  created_at: string;
  summary: string | null;
  user: MetabotUserInfo | null;
  message_count: number;
  total_tokens: number;
  model: string | null;
  slack_permalink: string | null;
  chat_messages: MetabotChatMessage[];
  queries: GeneratedQuery[];
  search_count: number;
  query_count: number;
  ip_address: string | null;
};

export type DataComplexityCatalogId = "library" | "universe" | "metabot";
// TODO snake_case everything
export type DataComplexityComponentId =
  | "entity-count"
  | "name-collisions"
  | "synonym-pairs"
  | "field-count"
  | "repeated-measures";

export type DataComplexitySubScore = {
  measurement: number;
  score: number;
  error?: string;
};

export type DataComplexityCatalog = {
  total: number;
  components: {
    [K in DataComplexityComponentId]: DataComplexitySubScore;
  };
};

export type DataComplexityScoresResponse = {
  meta: {
    "formula-version": number;
    "synonym-threshold": number;
    "embedding-model"?: {
      provider: string;
      "model-name": string;
    } | null;
  };
} & {
  [K in DataComplexityCatalogId]: DataComplexityCatalog;
};
