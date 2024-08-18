package otus.project.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;

public class Server {
  private static final Logger logger = LogManager.getLogger(Server.class.getName());
  private ServerSocket serverSocket;
  private final int port;
  private final List<ClientHandler> clients;
  private final AuthenticationProvider authenticationProvider;
  private final Timer timer;
  private final TimerTask timerTask;

  public Server(int port) {
    this.port = port;
    this.clients = new ArrayList<>();
    this.authenticationProvider = new DBProvider(this);
    this.timer = new Timer();
    this.timerTask = new TimerTask() {
      @Override
      public void run() {
        checkActiveClient();
      }
    };
    timer.schedule(timerTask, 60000L, 60000L);// Проверка каждую минуту
  }

  public AuthenticationProvider getAuthenticationProvider() {
    return authenticationProvider;
  }

  public void start() {
    try {
      serverSocket = new ServerSocket(port);
      logger.info("Сервер запущен на порту: " + port);
      authenticationProvider.initialize();
      while (true) {
        Socket socket = serverSocket.accept();
        new ClientHandler(this, socket);
      }
    } catch (Exception e) {
      logger.info("Сервер завершает работу", e);
      System.exit(0);
    }
  }

  public synchronized void subscribe(ClientHandler clientHandler) {
    broadcastMessage("В чат зашел: " + clientHandler.getUsername());
    clients.add(clientHandler);
  }

  public synchronized void unsubscribe(ClientHandler clientHandler) {
    clients.remove(clientHandler);
    if (clientHandler.getUsername() == null) {
      return;
    }
    broadcastMessage("Из чата вышел: " + clientHandler.getUsername());
  }

  public synchronized void broadcastMessage(String message) {
    message = getCurrentTime() + message;
    for (ClientHandler c : clients) {
      c.sendMessage(message);
    }
  }

  String getCurrentTime() {
    return "(" + new SimpleDateFormat("HH:mm:ss").format(new Date(System.currentTimeMillis())) + ") ";
  }

  public synchronized void sendPrivateMessage(ClientHandler ch, String message) {
    String[] str = message.split(" ", 3);
    if (str.length < 3) {
      return;
    }

    String mess = getCurrentTime() + ch.getUsername() + " -> " + str[1] + ": " + str[2];
    for (ClientHandler c : clients) {
      if (c.getUsername().equals(str[1])) {
        c.sendMessage(mess);
        ch.sendMessage(mess);
        break;
      }
    }
  }

  public synchronized boolean isUsernameBusy(String username) {
    for (ClientHandler c : clients) {
      if (c.getUsername().equals(username)) {
        return true;
      }
    }
    return false;
  }

  public synchronized void handleBan(ClientHandler ch, String name) {
    for (ClientHandler c : clients) {
      if (c.getUsername().equals(name)) {
        c.sendMessage("/banok");
        ch.sendMessage(getCurrentTime() + "Пользователь " + name + " заблокирован");
        c.setInChat(false);
        return;
      }
    }
    ch.sendMessage(getCurrentTime() + "Пользователя " + name + " нет в чате");
  }

  public synchronized void sendActiveList(ClientHandler clientHandler) {
    StringBuilder stringBuilder = new StringBuilder("В чате:\n");
    for (ClientHandler ch : clients) {
      stringBuilder.append(ch.getUsername()).append("\n");
    }
    clientHandler.sendMessage(stringBuilder.deleteCharAt(stringBuilder.length() - 1).toString());
  }

  private synchronized void checkActiveClient() {
    long currentTime = System.currentTimeMillis();
    for (ClientHandler ch : clients) {
      if ((currentTime - ch.getLastActive()) > 1_200_000L) {// 20 минут 1_200_000
        ch.sendMessage("Вы были не активны более 20 минут и покинули чат.");
        ch.disableClient();
      }
    }
  }

  public synchronized void shutdown() {
    for (ClientHandler ch : clients) {
      ch.disableClient();
    }
    try {
      serverSocket.close();
    } catch (IOException e) {
      logger.error("Не удалось закрыть serverSocket ", e);
    }
  }

}
