package mozhi.parseltongue.dao;

import mozhi.parseltongue.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserDao {

    User findById(@Param("id") Long id);

    User findByLogin(@Param("identifier") String identifier);

    User findByUsername(@Param("username") String username);

    User findByEmail(@Param("email") String email);

    int insert(User user);
}
