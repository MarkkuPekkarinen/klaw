package io.aiven.klaw.model.requests;

import static io.aiven.klaw.helpers.KwConstants.PASSWORD_REGEX;
import static io.aiven.klaw.helpers.KwConstants.PASSWORD_REGEX_VALIDATION_STR;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@ToString
@Getter
@Setter
public class UserInfoModel extends ProfileModel implements Serializable {

  @Size(min = 6, max = 300, message = "Username must be above 5 characters")
  @NotNull(message = "Username cannot be null")
  private String username;

  @NotNull private String role;

  @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_REGEX_VALIDATION_STR)
  @NotNull
  private String userPassword;

  @NotNull private Integer teamId;

  @NotNull private boolean switchTeams;

  private Set<Integer> switchAllowedTeamIds;

  private Set<String> switchAllowedTeamNames;

  private int tenantId;
}
