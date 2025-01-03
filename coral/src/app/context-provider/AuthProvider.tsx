import { createContext, ReactNode, useContext } from "react";
import { useQuery } from "@tanstack/react-query";
import { AuthUser, getAuth, isSuperAdmin } from "src/domain/auth-user";
import { BasePage } from "src/app/layout/page/BasePage";
import { Box, Icon } from "@aivenio/aquarium";
import loading from "@aivenio/aquarium/icons/loading";

/** We don't do Authentication on Corals side
 * at the moment, so we only have a AuthUser
 * in the context
 * */
const AuthContext = createContext<AuthUser>({
  username: "",
  userrole: "",
  teamname: "",
  teamId: "",
  canSwitchTeams: "",
  totalTeamTopics: 0,
  totalOrgTopics: 0,
  permissions: {
    canShutdownKw: false,
    canUpdatePermissions: false,
    addEditRoles: false,
    viewTopics: false,
    requestItems: false,
    viewKafkaConnect: false,
    syncBackTopics: false,
    syncBackSchemas: false,
    syncBackAcls: false,
    updateServerConfig: false,
    showServerConfigEnvProperties: false,
    addUser: false,
    addTeams: false,
    syncTopicsAcls: false,
    syncConnectors: false,
    manageConnectors: false,
    syncSchemas: false,
    approveAtleastOneRequest: false,
    approveDeclineTopics: false,
    approveDeclineOperationalReqs: false,
    approveDeclineSubscriptions: false,
    approveDeclineSchemas: false,
    approveDeclineConnectors: false,
    showAddDeleteTenants: false,
    addDeleteEditClusters: false,
    addDeleteEditEnvs: false,
  },
});

type UseAuthContext = AuthUser & { isSuperAdminUser: boolean };

const useAuthContext = (): UseAuthContext => {
  const authUser = useContext(AuthContext);

  const isSuperAdminUser = isSuperAdmin(authUser);

  return { ...authUser, isSuperAdminUser };
};

const AuthProvider = ({ children }: { children: ReactNode }) => {
  const { data: authUser, isLoading } = useQuery<AuthUser | undefined>(
    ["user-getAuth-data"],
    getAuth
  );

  if (!isLoading && authUser) {
    return (
      <AuthContext.Provider value={authUser}>{children}</AuthContext.Provider>
    );
  }

  return (
    <BasePage
      content={
        <Box paddingTop={"l2"} display={"flex"} justifyContent={"center"}>
          <div className={"visually-hidden"}>Loading Klaw</div>
          <Icon icon={loading} fontSize={"30px"} />
        </Box>
      }
    />
  );
};

export { useAuthContext, AuthProvider };
export type { UseAuthContext };
