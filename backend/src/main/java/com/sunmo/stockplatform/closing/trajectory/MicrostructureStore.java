package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import static com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.*;

@Repository
public class MicrostructureStore {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public MicrostructureStore(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public void save(Minute row) {
        try {
            jdbc.update("INSERT INTO closing_microstructure_minute(kind,symbol,start_time,finalized_at,payload) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                    row.kind(),row.symbol(),Timestamp.from(row.start()),Timestamp.from(row.finalizedAt()),mapper.writeValueAsString(row));
        } catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Microstructure serialization failed",error);}
    }
    public List<Minute> minutes(String symbol,Instant from,Instant until) {
        return jdbc.query("SELECT payload FROM closing_microstructure_minute WHERE symbol=? AND start_time>=? AND start_time<? ORDER BY start_time,kind",
                (rs,n)->{try{return mapper.readValue(rs.getString(1),Minute.class);}catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Microstructure read failed",error);}},
                symbol,Timestamp.from(from),Timestamp.from(until));
    }
}
