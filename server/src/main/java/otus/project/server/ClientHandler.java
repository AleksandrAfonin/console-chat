package otus.project.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class ClientHandler {
  private static final Logger logger = LogManager.getLogger(ClientHandler.class.getName());
  private Server server;
  private Socket socket;
  private DataInputStream in;
  private DataOutputStream out;
  private String username;
  private boolean inChat;
  private boolean isActive;
  private List<Role> userRoles;
  private long lastActive;

  public long getLastActive() {
    return lastActive;
  }

  public void setInChat(boolean inChat) {
    this.inChat = inChat;
  }

  public void setUserRole(Role userRole) {
    this.userRoles.add(userRole);
  }

  public void setUserRoles(List<Role> roles) {
    this.userRoles = roles;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public ClientHandler(Server server, Socket socket) throws IOException {
    this.server = server;
    this.socket = socket;
    this.in = new DataInputStream(socket.getInputStream());
    this.out = new DataOutputStream(socket.getOutputStream());
    this.inChat = true;
    this.userRoles = new ArrayList<>();
    this.lastActive = System.currentTimeMillis();
    this.isActive = true;
    new Thread(() -> {
      try {
        logger.info("Подключился новый клиент");
        while (true) {
          String message = in.readUTF();
          if (message.equals("/exit")) {
            sendMessage("/exitok");
            return;
          }
          if (message.startsWith("/auth ")) {
            String[] elements = message.split(" ");
            if (elements.length != 3) {
              sendMessage("Не верный формат команды /auth (/auth login password)");
              continue;
            }
            if (server.getAuthenticationProvider().authenticate(this, elements[1], elements[2])) {
              break;
            }
            continue;
          }
          if (message.startsWith("/register ")) {
            String[] elements = message.split(" ");
            if (elements.length != 4) {
              sendMessage("Не верный формат команды /register (/register login password username)");
              continue;
            }
            if (server.getAuthenticationProvider().registration(this, elements[1], elements[2], elements[3])) {
              break;
            }
            continue;
          }
          sendMessage("Перед работой с чатом необходимо выполнить аутентификацию '/auth login password' или регистрацию '/register login password username'");
        }
        while (isActive) {
          String message = in.readUTF();
          if (message.startsWith("/")) {
            if (message.equals("/exit")) {
              sendMessage("/exitok");
              break;
            }
            if (inChat) {
              if (message.startsWith("/w ")) {
                server.sendPrivateMessage(this, message);
                setNewLastActive();
              }
              if (message.startsWith("/ban ")) {
                if (isHaveRole(userRoles, Role.ADMIN)) {
                  String[] elements = message.split(" ");
                  if (elements.length != 2) {
                    sendMessage("Не верный формат команды /ban (/ban username)");
                    continue;
                  }
                  server.handleBan(this, elements[1]);
                } else {
                  sendMessage("У вас не достоточно прав для команды /ban");
                  continue;
                }
              }
              if (message.startsWith("/changenick ")){
                String[] elements = message.split(" ");
                if (elements.length != 2) {
                  sendMessage("Не верный формат команды /changenick (/changenick username)");
                  continue;
                }
                username = elements[1];
                sendMessage("Вы сменили ник на " + username);
              }
              if (message.startsWith("/activelist")) {
                server.sendActiveList(this);
              }
              if (message.startsWith("/shutdown")) {
                if (isHaveRole(userRoles, Role.ADMIN)) {
                  server.shutdown();
                } else {
                  sendMessage("У вас не достоточно прав для команды /shutdown");
                  continue;
                }
              }
            }
            continue;
          }
          if (inChat) {
            server.broadcastMessage(username + ": " + message);
            setNewLastActive();
          }
        }
      } catch (IOException e) {
        logger.error("Ошибка при получении сообщения от клиента из входящего потока", e);
      } finally {
        disconnect();
      }
    }).start();
  }

  private void setNewLastActive() {
    lastActive = System.currentTimeMillis();
  }

  private boolean isHaveRole(List<Role> userRoles, Role role) {
    for (Role r : userRoles) {
      if (r == role) {
        return true;
      }
    }
    return false;
  }

  public void sendMessage(String message) {
    if (message.equals("/exitok") || inChat) {
      try {
        out.writeUTF(message);
      } catch (IOException e) {
        logger.error("Ошибка при отправке сообщения клиенту в исходящий поток", e);
      }
    }
  }

  public void disableClient() {
    isActive = false;
    sendMessage("/exitok");
  }

  public void disconnect() {
    server.unsubscribe(this);
    try {
      if (in != null) {
        in.close();
      }
    } catch (IOException e) {
      logger.error("Ошибка при закрытии входящего потока", e);
    }
    try {
      if (out != null) {
        out.close();
      }
    } catch (IOException e) {
      logger.error("Ошибка при закрытии исходящего потока", e);
    }
    try {
      if (socket != null) {
        socket.close();
      }
    } catch (IOException e) {
      logger.error("Ошибка при закрытии сокета", e);
    }
  }
}