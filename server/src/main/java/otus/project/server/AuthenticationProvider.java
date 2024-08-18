package otus.project.server;

public interface AuthenticationProvider {
  void initialize();
  boolean authenticate(ClientHandler clientHandler, String login, String password);
  boolean registration(ClientHandler clientHandler, String login, String password, String username);

  boolean addRoleToUser(ClientHandler clientHandler, String username, String role);

  boolean delRoleForUser(ClientHandler clientHandler, String username, String role);
}
