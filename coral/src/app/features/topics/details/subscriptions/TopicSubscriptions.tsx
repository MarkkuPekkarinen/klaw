import { SegmentedControl, SegmentedControlGroup } from "@aivenio/aquarium";
import { useQuery } from "@tanstack/react-query";
import pick from "lodash/pick";
import { useMemo, useState } from "react";
import AclTypeFilter from "src/app/features/components/filters/AclTypeFilter";
import { SearchFilter } from "src/app/features/components/filters/SearchFilter";
import TeamFilter from "src/app/features/components/filters/TeamFilter";
import { useFiltersValues } from "src/app/features/components/filters/useFiltersValues";
import { TableLayout } from "src/app/features/components/layouts/TableLayout";
import { useTopicDetails } from "src/app/features/topics/details/TopicDetails";
import { TopicSubscriptionsTable } from "src/app/features/topics/details/subscriptions/TopicSubscriptionsTable";
import { getTopicOverview } from "src/domain/topic/topic-api";
import { AclOverviewInfo } from "src/domain/topic/topic-types";

type SubscriptionOptions =
  | "aclInfoList"
  | "prefixedAclInfoList"
  | "transactionalAclInfoList";
const isSubscriptionsOption = (value: string): value is SubscriptionOptions => {
  return [
    "aclInfoList",
    "prefixedAclInfoList",
    "transactionalAclInfoList",
  ].includes(value);
};

const TopicSubscriptions = () => {
  // @ TODO get environment from useTopicDetails too when it is implemented
  const { topicName } = useTopicDetails();
  const { search, teamId, aclType } = useFiltersValues();
  const {
    data,
    isLoading: dataIsLoading,
    isError,
    error,
  } = useQuery(["topic-overview"], {
    queryFn: () => getTopicOverview({ topicName, environmentId: "2" }),
  });

  const [selectedSubs, setSelectedSubs] =
    useState<SubscriptionOptions>("aclInfoList");

  const filteredData: AclOverviewInfo[] = useMemo(() => {
    if (data === undefined) {
      return [];
    }

    const subs = data[selectedSubs];

    if (subs === undefined) {
      return [];
    }

    return subs.filter((sub) => {
      const currentTeamId = String(sub.teamid);
      const teamFilter = teamId === "ALL" || currentTeamId === teamId;
      const searchFilter =
        search === "" ||
        JSON.stringify(pick(sub, "acl_ssl", "acl_ip"))
          .toLowerCase()
          .includes(search.toLowerCase());
      const aclTypeFilter =
        aclType === "ALL" || sub.topictype?.toUpperCase() === aclType;
      return teamFilter && aclTypeFilter && searchFilter;
    });
  }, [search, teamId, aclType, selectedSubs, data]);

  return (
    <TableLayout
      filters={[
        <TeamFilter key="team" />,
        <AclTypeFilter key="aclType" />,
        <SearchFilter
          key="search"
          placeholder="Search principal or IP"
          description={`Search for a partial match principals or IPs. Searching starts automatically with a little delay while typing. Press "Escape" to delete all your input.`}
        />,
        <SegmentedControlGroup
          name="Subscription options"
          key="subscription-options"
          onChange={(value: string) => {
            if (isSubscriptionsOption(value)) {
              setSelectedSubs(value);
            }
          }}
          value={selectedSubs}
        >
          <SegmentedControl name="User subscriptions" value="aclInfoList">
            User subs.
          </SegmentedControl>
          <SegmentedControl
            name="Prefixed subscriptions"
            value="prefixedAclInfoList"
          >
            Prefixed subs.
          </SegmentedControl>
          <SegmentedControl
            name="Transactional subscriptions"
            value="transactionalAclInfoList"
          >
            Transactional subs.
          </SegmentedControl>
        </SegmentedControlGroup>,
      ]}
      table={
        <TopicSubscriptionsTable
          selectedSubs={selectedSubs}
          filteredData={filteredData}
        />
      }
      isLoading={dataIsLoading}
      isErrorLoading={isError}
      errorMessage={error}
    />
  );
};

export default TopicSubscriptions;