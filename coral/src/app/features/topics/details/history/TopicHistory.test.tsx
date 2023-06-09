import { cleanup, screen, within } from "@testing-library/react";
import { useTopicDetails } from "src/app/features/topics/details/TopicDetails";
import { TopicHistory } from "src/app/features/topics/details/history/TopicHistory";
import { TopicOverview } from "src/domain/topic";
import { mockIntersectionObserver } from "src/services/test-utils/mock-intersection-observer";
import { customRender } from "src/services/test-utils/render-with-wrappers";

const testTopicHistoryList = [
  {
    environmentName: "DEV",
    teamName: "Richmond1",
    requestedBy: "tedlasso",
    requestedTime: "2022-Nov-04 14:41:18",
    approvedBy: "roykent",
    approvedTime: "2022-Nov-04 14:48:38",
    remarks: "Create",
  },
  {
    environmentName: "DEV",
    teamName: "Richmond2",
    requestedBy: "rebeccawelton",
    requestedTime: "2022-Nov-04 15:41:18",
    approvedBy: "Keeleyjones",
    approvedTime: "2022-Nov-04 15:48:38",
    remarks: "Delete",
  },
];
const testTopicOverview: TopicOverview = {
  topicExists: true,
  schemaExists: false,
  prefixAclsExists: false,
  txnAclsExists: false,
  topicInfoList: [
    {
      noOfPartitions: 1,
      noOfReplicas: "1",
      teamname: "Ospo",
      teamId: 1003,
      envId: "1",
      showEditTopic: true,
      showDeleteTopic: false,
      topicDeletable: false,
      envName: "DEV",
      topicName: "my awesome topic",
    },
  ],
  aclInfoList: [],
  prefixedAclInfoList: [],
  transactionalAclInfoList: [],
  topicHistoryList: testTopicHistoryList,
  topicPromotionDetails: {
    topicName: "aivtopic3",
    status: "NO_PROMOTION",
  },
  availableEnvironments: [
    {
      id: "1",
      name: "DEV",
    },
  ],
  topicIdForDocumentation: 1015,
};
const testTopicSchemas = {
  topicExists: true,
  schemaExists: true,
  prefixAclsExists: false,
  txnAclsExists: false,
  allSchemaVersions: [1],
  latestVersion: 1,
  schemaPromotionDetails: {
    DEV: {
      status: "success",
      sourceEnv: "3",
      targetEnv: "TST_SCH",
      targetEnvId: "9",
    },
  },
  schemaDetails: [
    {
      id: 2,
      version: 1,
      nextVersion: 0,
      prevVersion: 0,
      compatibility: "BACKWARD",
      content:
        '{\n  "doc" : "example",\n  "fields" : [ {\n    "default" : "6666665",\n    "doc" : "my test number",\n    "name" : "test",\n    "namespace" : "test",\n    "type" : "string"\n  } ],\n  "name" : "example",\n  "namespace" : "example",\n  "type" : "record"\n}',
      env: "DEV",
      showNext: false,
      showPrev: false,
      latest: true,
    },
  ],
};

jest.mock("src/app/features/topics/details/TopicDetails");

const mockUseTopicDetails = useTopicDetails as jest.MockedFunction<
  typeof useTopicDetails
>;

const columnsFieldMap = [
  { columnHeader: "Logs", relatedField: "remarks" },
  { columnHeader: "Team", relatedField: "teamName" },
  { columnHeader: "Requested by", relatedField: "requestedBy" },
  { columnHeader: "Requested on", relatedField: "requestedTime" },
  { columnHeader: "Approved by", relatedField: "approvedBy" },
  { columnHeader: "Approved on", relatedField: "approvedTime" },
];

describe("TopicHistory", () => {
  beforeAll(mockIntersectionObserver);

  describe("handles an empty history", () => {
    beforeAll(() => {
      mockUseTopicDetails.mockReturnValue({
        environmentId: "1",
        topicName: "hello",
        topicOverview: { ...testTopicOverview, topicHistoryList: [] },
        topicSchemas: testTopicSchemas,
      });

      customRender(<TopicHistory />, {
        memoryRouter: true,
      });
    });

    afterAll(() => {
      jest.clearAllMocks();
      cleanup();
    });

    it("shows the page header headline", () => {
      const headline = screen.getByRole("heading", {
        name: "History",
      });

      expect(headline).toBeVisible();
    });

    it("shows a headline informing user about missing history", () => {
      const headline = screen.getByRole("heading", {
        name: "No Topic history",
      });

      expect(headline).toBeVisible();
    });

    it("shows information about missing history", () => {
      const infoText = screen.getByText("This Topic contains no history.");

      expect(infoText).toBeVisible();
    });

    it("does not render a table", () => {
      const table = screen.queryByRole("table");

      expect(table).not.toBeInTheDocument();
    });
  });

  describe("shows a table with topics history", () => {
    beforeAll(() => {
      mockUseTopicDetails.mockReturnValue({
        environmentId: "1",
        topicName: "hello",
        topicOverview: testTopicOverview,
        topicSchemas: testTopicSchemas,
      });

      customRender(<TopicHistory />, {
        memoryRouter: true,
      });
    });

    afterAll(() => {
      jest.clearAllMocks();
      cleanup();
    });

    it("shows the page header headline", () => {
      const headline = screen.getByRole("heading", {
        name: "History",
      });

      expect(headline).toBeVisible();
    });

    it("shows a table for topics history", () => {
      const table = screen.getByRole("table", {
        name: "Topic history",
      });

      expect(table).toBeVisible();
    });

    it("shows all column headers", () => {
      const table = screen.getByRole("table", {
        name: "Topic history",
      });
      const header = within(table).getAllByRole("columnheader");

      expect(header).toHaveLength(columnsFieldMap.length);
    });

    it("shows a row for each given requests plus header row", () => {
      const table = screen.getByRole("table", {
        name: "Topic history",
      });
      const row = within(table).getAllByRole("row");

      expect(row).toHaveLength(testTopicHistoryList.length + 1);
    });

    it(`renders the right amount of cells based in topics history list`, () => {
      const table = screen.getByRole("table", {
        name: "Topic history",
      });
      const cells = within(table).getAllByRole("cell");

      expect(cells).toHaveLength(
        columnsFieldMap.length * testTopicHistoryList.length
      );
    });

    columnsFieldMap.forEach((column) => {
      it(`shows a column header for ${column.columnHeader}`, () => {
        const table = screen.getByRole("table", {
          name: "Topic history",
        });
        const header = within(table).getByRole("columnheader", {
          name: column.columnHeader,
        });

        expect(header).toBeVisible();
      });

      testTopicHistoryList.forEach((historyEntry, index) => {
        it(`shows field ${column.relatedField} for history entry number ${index}`, () => {
          const table = screen.getByRole("table", {
            name: "Topic history",
          });

          // eslint-disable-next-line @typescript-eslint/ban-ts-comment
          //@ts-ignore
          const field = historyEntry[column.relatedField];

          let text = field;
          if (
            column.columnHeader === "Requested on" ||
            column.columnHeader === "Approved on"
          ) {
            text = `${field}${"\u00A0"}UTC`;
          }

          const cell = within(table).getByRole("cell", { name: text });

          expect(cell).toBeVisible();
        });
      });
    });
  });
});