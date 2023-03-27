import { NativeSelect, Option } from "@aivenio/aquarium";
import { useQuery } from "@tanstack/react-query";
import { useFiltersValues } from "src/app/features/components/filters/useFiltersValues";
import {
  Environment,
  getEnvironments,
  getSchemaRegistryEnvironments,
} from "src/domain/environment";
import { HTTPError } from "src/services/api";

interface EnvironmentFilterProps {
  isSchemaRegistryEnvironments?: boolean;
}

function EnvironmentFilter({
  isSchemaRegistryEnvironments = false,
}: EnvironmentFilterProps) {
  const { environment, setFilterValue } = useFiltersValues();

  const { data: environments } = useQuery<Environment[], HTTPError>(
    ["topic-environments"],
    {
      queryFn: isSchemaRegistryEnvironments
        ? getSchemaRegistryEnvironments
        : getEnvironments,
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