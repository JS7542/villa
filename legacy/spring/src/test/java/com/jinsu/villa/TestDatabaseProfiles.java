package com.jinsu.villa;

import org.springframework.test.context.ActiveProfilesResolver;

/** Tests exercise the same PostgreSQL profile as deployment, with an isolated local DB. */
public class TestDatabaseProfiles implements ActiveProfilesResolver {
  @Override
  public String[] resolve(Class<?> testClass) {
    String url = System.getenv("TEST_DB_URL");
    return url != null && url.startsWith("jdbc:postgresql:")
        ? new String[] {"test", "postgres"}
        : new String[] {"test"};
  }
}
