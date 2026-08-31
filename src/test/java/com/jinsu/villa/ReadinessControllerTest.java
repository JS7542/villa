package com.jinsu.villa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.jinsu.villa.common.controller.ReadinessController;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ReadinessControllerTest {
  @Test
  void unavailableDatabaseReturns503WithoutLeakingConnectionDetails() throws Exception {
    DataSource source = mock(DataSource.class);
    when(source.getConnection()).thenThrow(new SQLException("password=do-not-leak host=private"));
    var response = new ReadinessController(source).ready();
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody()).containsEntry("status", "DOWN");
    assertThat(response.getBody().toString()).doesNotContain("password", "private");
  }
}
