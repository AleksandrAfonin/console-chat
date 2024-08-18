package otus.project.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

public class Client {
  private static final Logger logger = LogManager.getLogger(Client.class.getName());
  private Socket socket;
  private DataInputStream in;
  private DataOutputStream out;
  private boolean isEnabled;

  public Client() throws IOException {
    Scanner scanner = new Scanner(System.in);
    this.socket = new Socket("localhost", 8189);
    this.in = new DataInputStream(socket.getInputStream());
    this.out = new DataOutputStream(socket.getOutputStream());
    this.isEnabled = true;

    new Thread(() -> {
      try {
        while (true) {
          String message = in.readUTF();
          if (message.equals("/exitok")) {
            isEnabled = false;
            break;
          }
          if (message.startsWith("/authok")) {
            System.out.println("Удалось успешно войти в чат под именем пользователя: " + message.split(" ")[1]);
            continue;
          }
          if (message.startsWith("/regok")) {
            System.out.println("Удалось успешно пройти регистрацию и войти в чат под именем пользователя: " + message.split(" ")[1]);
            continue;
          }
          if (message.equals("/banok")) {
            System.out.println("Вы заблокированы");
            continue;
          }
          System.out.println(message);
        }
      } catch (IOException e) {
        logger.error("Ошибка при получении сообщения из входящего потока", e);
      } finally {
        disconnect();
      }
    }).start();
    while (true) {
      String message = scanner.nextLine();
      if (isEnabled) {
        out.writeUTF(message);
        if (message.equals("/exit")) {
          break;
        }
      } else {
        break;
      }
    }
  }

  private void disconnect() {
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
