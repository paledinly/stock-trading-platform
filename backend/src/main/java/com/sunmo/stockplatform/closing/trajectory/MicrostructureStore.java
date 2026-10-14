/*
 * 미세구조 1분 집계를 closing_microstructure_minute 표에 JSON으로 저장하고 읽는 저장소예요.
 * JdbcTemplate로 SQL을 직접 실행하고, 같은 종류·종목·분이 이미 있으면 덮어쓰지 않아요.
 * MicrostructureService와 TrajectoryController가 사용해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import static com.sunmo.stockplatform.closing.trajectory.MicrostructureModel.*;

/**
 * 미세구조 1분 집계를 다루는 레포지토리(DB 창구)예요.
 * 한 번 꽂은 앨범 사진은 바꾸지 않는 사진첩처럼, 같은 칸에 두 번째 사진이 오면 무시해요.
 */
@Repository
public class MicrostructureStore {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    /** 하는 일: DB 실행기와 JSON 변환기를 받아 보관해요. 받는 것: JdbcTemplate, ObjectMapper. 돌려주는 것: 저장소 객체예요. */
    public MicrostructureStore(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    /** 하는 일: 미세구조 1분 집계 한 줄을 저장해요(이미 있으면 무시). 받는 것: Minute. 돌려주는 것: 없어요. */
    public void save(Minute row) {
        try {
            jdbc.update("INSERT INTO closing_microstructure_minute(kind,symbol,start_time,finalized_at,payload) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                    row.kind(),row.symbol(),Timestamp.from(row.start()),Timestamp.from(row.finalizedAt()),mapper.writeValueAsString(row));
        } catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Microstructure serialization failed",error);}
    }
    /** 하는 일: 한 종목의 미세구조 1분 집계를 시간·종류 순으로 읽어요. 받는 것: 종목, 시작(포함), 끝(제외). 돌려주는 것: Minute 목록이에요. */
    public List<Minute> minutes(String symbol,Instant from,Instant until) {
        return jdbc.query("SELECT payload FROM closing_microstructure_minute WHERE symbol=? AND start_time>=? AND start_time<? ORDER BY start_time,kind",
                (rs,n)->{try{return mapper.readValue(rs.getString(1),Minute.class);}catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Microstructure read failed",error);}},
                symbol,Timestamp.from(from),Timestamp.from(until));
    }
}
