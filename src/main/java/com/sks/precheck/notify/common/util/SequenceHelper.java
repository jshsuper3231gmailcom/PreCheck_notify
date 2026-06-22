package com.sks.precheck.notify.common.util;

import com.sks.precheck.notify.common.exception.NotifyException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class SequenceHelper {

    private final DataSource dataSource;

    public SequenceHelper(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Long nextval(String sequenceName) {
        try (Connection connection = dataSource.getConnection()) {
            String dbProductName = connection.getMetaData().getDatabaseProductName();
            String sql = buildNextvalSql(dbProductName, sequenceName);

            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NotifyException("SEQUENCE nextval 조회 결과가 없다: " + sequenceName);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new NotifyException("SEQUENCE nextval 조회 실패: " + sequenceName, e);
        }
    }

    private String buildNextvalSql(String dbProductName, String sequenceName) {
        // PostgreSQL: nextval('seq_name'), Altibase(Oracle 방언): seq_name.NEXTVAL FROM DUAL
        if (dbProductName != null && dbProductName.toLowerCase().contains("postgres")) {
            return "select nextval('" + sequenceName + "')";
        }
        return "SELECT " + sequenceName + ".NEXTVAL FROM DUAL";
    }
}
