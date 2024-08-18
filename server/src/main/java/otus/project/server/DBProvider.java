package otus.project.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

public class DBProvider implements AuthenticationProvider {
  private static final Logger logger = LogManager.getLogger(DBProvider.class.getName());
  private final Server server;

  private final String DATABASE_URL = "jdbc:sqlite:databases/users.db";
  private final String USERNAME_BY_LOGIN_AND_PASSWORD = "SELECT username FROM users WHERE login = ? AND password = ? AND isdeleted = false";
  private final String IS_LOGIN_EXISTS = "SELECT login FROM users WHERE login = ?";
  private final String IS_USERNAME_EXISTS = "SELECT username FROM users WHERE username = ?";
  private final String GET_USERID_BY_NAME = "SELECT id FROM users WHERE username = ?";
  private final String GET_ROLEID_BY_ROLENAME = "SELECT id FROM roles WHERE role = ?";
  private final String DELETE_ROLE_FOR_USER = "DELETE FROM roles_to_users WHERE id_user = ? AND id_role = ?";
  private final String GET_USER_ROLES_BY_NAME = """
          SELECT roles.role FROM roles, roles_to_users, users WHERE
           roles_to_users.id_role = roles.id AND
           roles_to_users.id_user = users.id AND
           users.username = ?
          """;
  private final String ADD_NEW_USER = """
          INSERT INTO users
           (login, password, username, date, isdeleted)
           VALUES (?, ?, ?, ?, ?)
           """;
  private final String ADD_ROLE_TO_USER = """
          INSERT INTO roles_to_users
           (id_user, id_role)
           VALUES (?, ?)
           """;

  public DBProvider(Server server) {
    this.server = server;
  }

  @Override
  public void initialize() {
    logger.info("Сервис аутентификации запущен: JDBC режим");
  }

  /**
   * Аутентификация пользователя в чате
   *
   * @param clientHandler клиент-обработчик
   * @param login         логин пользователя
   * @param password      пароль пользователя
   * @return true/false успех/неудача
   */
  @Override
  public boolean authenticate(ClientHandler clientHandler, String login, String password) {
    String authUsername = getUsernameByLoginAndPassword(login, password);
    if (authUsername == null) {
      clientHandler.sendMessage("Некорректный логин/пароль или пользователя нет в системе");
      return false;
    }
    if (server.isUsernameBusy(authUsername)) {
      clientHandler.sendMessage("Указанная учетная запись уже занята");
      return false;
    }
    clientHandler.setUsername(authUsername);
    clientHandler.setUserRoles(getUserRolesByUsername(authUsername));
    server.subscribe(clientHandler);
    clientHandler.sendMessage("/authok " + authUsername);
    return true;
  }

  /**
   * Регистрация нового пользователя вход в чат
   *
   * @param clientHandler клиент-обработчик
   * @param login         логин пользователя
   * @param password      пароль пользователя
   * @param username      имя пользователя
   * @return true/false успех/неудача
   */
  @Override
  public boolean registration(ClientHandler clientHandler, String login, String password, String username) {
    if (login.trim().length() < 3 || password.trim().length() < 6 || username.trim().length() < 1) {
      clientHandler.sendMessage("Логин 3+ символа, Пароль 6+ символов, Имя пользователя 1+ символ");
      return false;
    }
    if (isLoginAlreadyExists(login)) {
      clientHandler.sendMessage("Указанный логин уже занят");
      return false;
    }
    if (isUsernameAlreadyExists(username)) {
      clientHandler.sendMessage("Указанное имя пользователя уже занято");
      return false;
    }

    if (addNewUser(clientHandler, login, password, username)) {
      clientHandler.setUsername(username);
      clientHandler.setUserRole(Role.USER);
      server.subscribe(clientHandler);
      clientHandler.sendMessage("/regok " + username);
      return true;
    }
    return false;
  }

  /**
   * Добавление нового пользователя в БД
   *
   * @param clientHandler клиент-обработчик
   * @param login         логин пользователя
   * @param password      пароль пользователя
   * @param username      имя пользователя
   * @return true/false успех/неудача
   */
  private boolean addNewUser(ClientHandler clientHandler, String login, String password, String username) {
    try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
      try (PreparedStatement statement = connection.prepareStatement(ADD_NEW_USER)) {
        statement.setString(1, login);
        statement.setString(2, password);
        statement.setString(3, username);
        statement.setString(4, (new SimpleDateFormat("dd.MM.yyyy")).format(new Date(System.currentTimeMillis())));
        statement.setBoolean(5, false);
        if (statement.executeUpdate() == 0) {
          clientHandler.sendMessage("Не удалось добавить пользователя в базу данных.");
          return false;
        }
      }
    } catch (SQLException e) {
      logger.error("Ошибка при добавлении нового пользователя в базу данных", e);
      throw new RuntimeException(e);
    }
    return addRoleToUserByUsername(clientHandler, username, Role.USER.name());
  }

  /**
   * Добавление новой роли пользователю по его имени
   *
   * @param clientHandler клиент-обработчик
   * @param username      имя пользователя
   * @param role          роль
   * @return true/false успех/неудача
   */
  private boolean addRoleToUserByUsername(ClientHandler clientHandler, String username, String role) {
    int id_user;
    if ((id_user = getUserIdByName(username)) < 0) {
      clientHandler.sendMessage("Пользователь по имени " + username + " не найден в базе данных");
      return false;
    }
    int id_role;
    if ((id_role = getRoleIdByRolename(role)) < 0) {
      clientHandler.sendMessage("Роль '" + role.toUpperCase() + "' не найдена в базе данных");
      return false;
    }

    try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
      try (PreparedStatement statement = connection.prepareStatement(ADD_ROLE_TO_USER)) {
        statement.setInt(1, id_user);
        statement.setInt(2, id_role);
        if (statement.executeUpdate() != 0) {
          clientHandler.sendMessage("Для пользователя " + username + " добавлена роль '" + role.toUpperCase() + "'");
          return true;
        } else {
          return false;
        }
      }
    } catch (SQLException e) {
      logger.error("Ошибка при добавлении новой роли для нового пользователя в базу данных", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Получить id роли по названию rolename
   *
   * @param name имя роли
   * @return id идентификатор роли
   */
  private int getRoleIdByRolename(String name) {
    try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
      try (PreparedStatement statement = connection.prepareStatement(GET_ROLEID_BY_ROLENAME)) {
        statement.setString(1, name.toUpperCase());
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            int id_role = resultSet.getInt("id");
            return id_role;
          } else {
            return -1;
          }
        }
      }
    } catch (SQLException e) {
      logger.error("Ошибка при добавлении нового пользователя в базу данных", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Получить id пользователя по его имени
   *
   * @param username имя пользователя
   * @return id идентификатор пользователя
   */
  private int getUserIdByName(String username) {
    try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
      try (PreparedStatement statement = connection.prepareStatement(GET_USERID_BY_NAME)) {
        statement.setString(1, username);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            int id_user = resultSet.getInt("id");
            return id_user;
          } else {
            return -1;
          }
        }
      }
    } catch (SQLException e) {
      logger.error("Ошибка при получении id пользователя по его имени из БД", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Получение имени ползователя по логину и паролю
   *
   * @param login    логин
   * @param password пароль
   * @return имя пользователя/null
   */
  private String getUsernameByLoginAndPassword(String login, String password) {
    try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
      try (PreparedStatement statement = connection.prepareStatement(USERNAME_BY_LOGIN_AND_PASSWORD)) {
        statement.setString(1, login);
        statement.setString(2, password);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            return resultSet.getString("username");
          } else {
            return null;
          }
        }
      }
    } catch (SQLException e) {
      logger.error("Ошибка при получение имени ползователя по логину и паролю из БД", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Проверка на уже существование логина
   *
   * @param login логин
   * @return true/false существует/не существует
   */
  private boolean isLoginAlreadyExists(String login) {
    try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
      try (PreparedStatement statement = connection.prepareStatement(IS_LOGIN_EXISTS)) {
        statement.setString(1, login);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next();
        }
      }
    } catch (SQLException e) {
      logger.error("Ошибка при проверке наличия логина в БД", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Проверка на уже существование имени ползователя
   *
   * @param username имя пользователя
   * @return true/false существует/не существует
   */
  private boolean isUsernameAlreadyExists(String username) {
    try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
      try (PreparedStatement statement = connection.prepareStatement(IS_USERNAME_EXISTS)) {
        statement.setString(1, username);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next();
        }
      }
    } catch (SQLException e) {
      logger.error("Ошибка при проверке на существование имени ползователя в БД", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Получение списка ролей пользователя по его имени
   *
   * @param name имя пользователя
   * @return список ролей
   */
  private List<Role> getUserRolesByUsername(String name) {
    List<Role> roles = new ArrayList<>();
    try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
      try (PreparedStatement statement = connection.prepareStatement(GET_USER_ROLES_BY_NAME)) {
        statement.setString(1, name);
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            String role = resultSet.getString("role");
            for (Role r : Role.values()) {
              if (r.name().equalsIgnoreCase(role)) {
                roles.add(r);
                break;
              }
            }
          }
          return roles;
        }
      }
    } catch (SQLException e) {
      logger.error("Ошибка при получении списка ролей пользователя по его имени из БД", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Добавление роли для пользователя
   *
   * @param clientHandler клиент-обработчик
   * @param username      имя пользователя
   * @param role          роль
   * @return true/false успех/неудача
   */
  @Override
  public boolean addRoleToUser(ClientHandler clientHandler, String username, String role) {
    if (getUserIdByName(username) > -1) {
      List<Role> roles = getUserRolesByUsername(username);
      for (Role r : roles) {
        if (r.name().equalsIgnoreCase(role)) {
          clientHandler.sendMessage("У пользователя " + username + " есть роль '" + role.toUpperCase() + "'");
          return false;
        }
      }
      return addRoleToUserByUsername(clientHandler, username, role);
    } else {
      clientHandler.sendMessage("Нет пользователя " + username + " в базе данных");
      return false;
    }
  }

  @Override
  public boolean delRoleForUser(ClientHandler clientHandler, String username, String role) {
    int id_user = getUserIdByName(username);
    if (id_user > -1) {
      int id_role = getRoleIdByRolename(role);
      if (id_role > -1) {
        List<Role> roles = getUserRolesByUsername(username);
        for (Role r : roles) {
          if (r.name().equalsIgnoreCase(role)) {
            if (delRoleToUserByUsername(id_user, id_role)) {
              clientHandler.sendMessage("У пользователя " + username + " удалена роль '" + role.toUpperCase() + "'");
              return true;
            } else {
              return false;
            }
          }
        }
        clientHandler.sendMessage("У пользователя " + username + " нет роли '" + role.toUpperCase() + "'");
      } else {
        clientHandler.sendMessage("Нет роли '" + role.toUpperCase() + "' в базе данных");
      }
      return false;
    }
    clientHandler.sendMessage("Нет пользователя " + username + " в базе данных");
    return false;
  }

  private boolean delRoleToUserByUsername(int id_user, int id_role) {
    try (Connection connection = DriverManager.getConnection(DATABASE_URL)) {
      try (PreparedStatement statement = connection.prepareStatement(DELETE_ROLE_FOR_USER)) {
        statement.setInt(1, id_user);
        statement.setInt(2, id_role);
        return statement.executeUpdate() > 0;
      }
    } catch (SQLException e) {
      logger.error("Ошибка при удалении роли у пользователя с заданным именем из БД", e);
      throw new RuntimeException(e);
    }
  }
}