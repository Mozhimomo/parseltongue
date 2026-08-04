package mozhi.parseltongue.service;

import mozhi.parseltongue.dao.UserDao;
import mozhi.parseltongue.dto.UserDTO;
import mozhi.parseltongue.entity.User;
import mozhi.parseltongue.exception.ApiException;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserDao userDao;

    public UserService(UserDao userDao) {
        this.userDao = userDao;
    }

    public UserDTO getById(Long id) {
        User user = userDao.findById(id);
        if (user == null) {
            throw ApiException.badRequest("用户不存在");
        }
        return UserDTO.from(user);
    }
}
