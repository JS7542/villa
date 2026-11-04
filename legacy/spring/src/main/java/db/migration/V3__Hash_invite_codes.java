package db.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V3__Hash_invite_codes extends BaseJavaMigration {
  public void migrate(Context context) throws Exception {
    try (var q = context.getConnection().prepareStatement("SELECT id, code FROM invite_codes");
        var rs = q.executeQuery();
        var u =
            context
                .getConnection()
                .prepareStatement(
                    "UPDATE invite_codes SET code=?, status=CASE WHEN status='ACTIVE' THEN"
                        + " 'REVOKED' ELSE status END WHERE id=?")) {
      while (rs.next()) {
        u.setString(
            1,
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(rs.getString(2).getBytes(StandardCharsets.UTF_8))));
        u.setLong(2, rs.getLong(1));
        u.addBatch();
      }
      u.executeBatch();
    }
  }
}
