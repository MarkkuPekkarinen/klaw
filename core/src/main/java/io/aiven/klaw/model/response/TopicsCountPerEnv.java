package io.aiven.klaw.model.response;

import lombok.Data;

@Data
public class TopicsCountPerEnv {
  private String status;

  private String topicsCount;
}
