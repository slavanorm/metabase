import { P, match } from "ts-pattern";
import { t } from "ttag";

import { getErrorMessage } from "metabase/api/utils";
import {
  Box,
  Button,
  Card,
  SimpleGrid,
  Skeleton,
  Stack,
  Text,
} from "metabase/ui";
import { formatNumber } from "metabase/utils/formatting";

import {
  useGetDataComplexityScoresQuery,
  useRefreshDataComplexityScoresMutation,
} from "../../api";
import type {
  DataComplexityCatalog,
  DataComplexityCatalogId,
} from "../../types";

function DataComplexityCardSkeleton() {
  return (
    <Card withBorder shadow="none" p="md">
      <Box my={5} w="70%">
        <Skeleton h={14} />
      </Box>
      <Box my={5} w="40%">
        <Skeleton h={14} />
      </Box>
      <Stack align="center" gap={0}>
        <Skeleton h="4rem" w="30%" />
        <Box my={5} w="40%">
          <Skeleton h={14} />
        </Box>
      </Stack>
    </Card>
  );
}

function DataComplexityCard({
  catalogId,
  catalog,
}: {
  catalogId: DataComplexityCatalogId;
  catalog: DataComplexityCatalog;
}) {
  return (
    <Card withBorder shadow="none" p="md">
      <Text fw={700}>
        {match(catalogId)
          // TODO strings
          .with("library", () => t`Library semantic layer data complexity`)
          .with("universe", () => t`Universe semantic layer data complexity`)
          .with("metabot", () => t`Metabot semantic layer data complexity`)
          .exhaustive()}
      </Text>
      <Text c="text-secondary">
        {/* TODO strings */}
        {t`74th percentile globally`}
      </Text>
      <Stack align="center" gap={0}>
        <Text size="4rem" fw={700} c="error">
          {formatNumber(catalog.total, { maximumFractionDigits: 0 })}
        </Text>
        {/* TODO strings */}
        <Text c="text-secondary">{t`This is fine for Opus`}</Text>
      </Stack>
    </Card>
  );
}

export function DataComplexityCards() {
  const {
    data,
    isLoading,
    error: queryError,
  } = useGetDataComplexityScoresQuery();
  const [
    refreshDataComplexityScores,
    { isLoading: isRefreshing, error: refreshError },
  ] = useRefreshDataComplexityScoresMutation();

  const handleRefresh = async () => {
    await refreshDataComplexityScores().unwrap();
  };

  return (
    <div>
      <Button variant="outline" loading={isRefreshing} onClick={handleRefresh}>
        {t`Test refresh`}
      </Button>
      {refreshError ? (
        <Text size="sm" c="error">
          {getErrorMessage(refreshError, t`Refreshing these scores failed.`)}
        </Text>
      ) : null}

      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
        {match({ isLoading, queryError, data })
          .with({ isLoading: true }, () => (
            <>
              <DataComplexityCardSkeleton />
              <DataComplexityCardSkeleton />
            </>
          ))
          .with(
            { queryError: P.nonNullable },
            { data: P.nullish },
            ({ queryError }) => (
              <Card withBorder shadow="none" p="md">
                <Text fw={700}>{t`Data complexity scores`}</Text>
                <Text mt="xs" size="sm" c="text-secondary">
                  {getErrorMessage(
                    queryError,
                    t`These scores are unavailable right now.`,
                  )}
                </Text>
              </Card>
            ),
          )
          .with({ data: P.nonNullable }, ({ data }) =>
            (["library", "metabot"] as const).map((key) => (
              <DataComplexityCard
                key={key}
                catalogId={key}
                catalog={data[key]}
              />
            )),
          )
          .exhaustive()}
      </SimpleGrid>
    </div>
  );
}
