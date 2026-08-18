package mozhi.parseltongue.dao;

import mozhi.parseltongue.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserDao {

    User findById(@Param("id") Long id);

    User findByUsername(@Param("username") String username);

    int insert(User user);
}
