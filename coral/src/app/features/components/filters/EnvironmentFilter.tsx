import { NativeSelect, Option } from "@aivenio/aquarium";
import { useQuery } from "@tanstack/react-query";
import { useFiltersContext } from "src/app/features/components/filters/useFiltersContext";
import { Environment } from "src/domain/environment";
import { HTTPError } from "src/services/api";
import { useApiConfig } from "src/app/context-provider/ApiProvider";

type EnvironmentFor = "TOPIC_AND_ACL" | "SCHEMA" | "CONNECTOR";
interface EnvironmentFilterProps {
  environmentsFor: EnvironmentFor;
}

function filterEnvironmentsForSchema(
  environments: Environment[]
): Environment[] {
  return environments
    .map((env) => {
      if (env.associatedEnv) {
        return {
          ...env,
          id: env.associatedEnv.id,
          name: env.associatedEnv.name,
        };
      }
    })
    .filter((entry) => entry !== undefined) as Environment[];
}

function EnvironmentFilter({ environmentsFor }: EnvironmentFilterProps) {
  const apiConfig = useApiConfig();
  const { environment, setFilterValue } = useFiltersContext();

  const environmentEndpointMap: {
    [key in EnvironmentFor]: {
      apiEndpoint: () => Promise<Environment[]>;
      // we use the api function name as query usually, so we
      // want to keep that pattern here, too.
      queryFn: string;
    };
  } = {
    TOPIC_AND_ACL: {
      apiEndpoint: apiConfig.getAllEnvironmentsForTopicAndAcl,
      queryFn: "getAllEnvironmentsForTopicAndAcl",
    },
    SCHEMA: {
      apiEndpoint: apiConfig.getAllEnvironmentsForTopicAndAcl,
      queryFn: "getAllEnvironmentsForTopicAndAcl",
    },
    CONNECTOR: {
      apiEndpoint: apiConfig.getAllEnvironmentsForConnector,
      queryFn: "getAllEnvironmentsForConnector",
    },
  };

  const { data: environments } = useQuery<Environment[], HTTPError>(
    [environmentEndpointMap[environmentsFor].queryFn],
    {
      queryFn: environmentEndpointMap[environmentsFor].apiEndpoint,
      select: (environments) => {
        if (environmentsFor === "SCHEMA") {
          return filterEnvironmentsForSchema(environments);
        }
        return environments;
      },
    }
  );

  if (!environments) {
    return (
      <div data-testid={"select-environment-loading"}>
        <NativeSelect.Skeleton />
      </div>
    );
  } else {
    return (
      <NativeSelect
        labelText="Filter by Environment"
        value={environment}
        onChange={(event) =>
          setFilterValue({ name: "environment", value: event.target.value })
        }
      >
        <Option key={"ALL"} value={"ALL"}>
          All Environments
        </Option>

        {environments.map((env: Environment) => (
          <Option key={env.id} value={env.id}>
            {env.name}
          </Option>
        ))}
      </NativeSelect>
    );
  }
}

export default EnvironmentFilter;
