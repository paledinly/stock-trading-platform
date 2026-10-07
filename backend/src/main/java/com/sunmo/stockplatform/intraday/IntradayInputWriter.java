package com.sunmo.stockplatform.intraday;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.List;

/** Shares the recommendation transaction; generated IDs are needed only for later replay. */
@Repository
public class IntradayInputWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public IntradayInputWriter(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void save(List<IntradayModel.Input> inputs) {
        if(inputs.isEmpty()) return;
        // Multi-row SQL avoids one network round trip per IDENTITY insert, without driver flags.
        for(int from=0;from<inputs.size();from+=250) {
            var batch=inputs.subList(from,Math.min(from+250,inputs.size()));
            var args=new java.util.ArrayList<Object>();
            for(var input:batch) {
                args.add(input.observation().tick().businessDate());
                args.add(input.observation().tick().stockCode());
                args.add(Timestamp.from(input.observation().receivedAt()));
                args.add(Timestamp.from(input.evaluatedAt()));
                try { args.add(mapper.writeValueAsString(input)); }
                catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Input serialization failed",e); }
            }
            jdbc.update("INSERT INTO intraday_input(session_date,stock_code,received_at,evaluated_at,payload) VALUES "
                    + String.join(",",java.util.Collections.nCopies(batch.size(),"(?,?,?,?,?)")),args.toArray());
        }
    }
}
