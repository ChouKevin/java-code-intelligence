package com.example.syntax;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis annotation SQL 的各種寫法 */
@Mapper
public interface AccountMapper {

    /** 單成員形式 */
    @Select("SELECT * FROM accounts WHERE id = #{id}")
    String findById(Long id);

    /** value= 具名形式，舊實作回傳 null */
    @Select(value = "SELECT * FROM accounts WHERE code = #{code}")
    String findByCode(String code);

    /** 多行陣列形式，舊實作回傳字面的大括號字串 */
    @Select({"SELECT *", "FROM accounts", "WHERE status = #{status}"})
    String findByStatus(String status);

    @Update("UPDATE accounts SET status = #{status} WHERE id = #{id}")
    int updateStatus(Long id, String status);

    /** 巢狀 mapper，其 FQN 必須包含外層型別 */
    @Mapper
    interface Nested {

        @Select("SELECT 1")
        String ping();
    }
}
