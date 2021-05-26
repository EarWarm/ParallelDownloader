package ru.earwarm.programms.paralleldownloader;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.xml.bind.DatatypeConverter;

public class Downloader {

  private String userAgent = "Mozilla/5.0 (Linux; arm_64; Android 5.1; m3 note) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.128 YaBrowser/21.3.3.153.00 SA/3 Mobile Safari/537.36";

  private final int threadCount;
  private int reconnectCount = 3;

  public Downloader(int threadCount) {
    this.threadCount = threadCount > 0 ? threadCount : 1;
  }

  public Downloader(int threadCount, int reconnectCount) {
    this(threadCount);
    this.reconnectCount = reconnectCount > 0 ? reconnectCount : 3;
  }

  public Downloader(String userAgent, int threadCount) {
    this(threadCount);
    if (userAgent != null && !userAgent.isEmpty()) {
      this.userAgent = userAgent;
    }
  }

  public Downloader(String userAgent, int threadCount, int reconnectCount) {
    this(userAgent, threadCount);
    this.reconnectCount = reconnectCount > 0 ? reconnectCount : 3;
  }

  public void download(URL url, File file) throws IOException, NullPointerException {
    long fileSize = getFileSize(url);
    if (fileSize <= 0) {
      throw new IOException("Ошибка размера файла. Вес файла меньше 1 байта");
    }

    int oneThreadFileSize = (int) (fileSize / this.threadCount);
    if (oneThreadFileSize <= 0) {
      throw new IOException(
          "Ошибка размера файла на поток. Размер файла на 1 поток меньше 1 байта");
    }

    if (!file.getParentFile().exists()) {
      if (!file.getParentFile().mkdirs()) {
        throw new IOException("Ошибка создания директории(ий) для файла.");
      }
    }
    if (file.exists()) {
      if (file.createNewFile()) {
        throw new IOException("Ошибка создания файла.");
      }
    }

    AtomicLong downloadedSize = new AtomicLong();

    String tempName = getTempFileName(url.toString(), file.getAbsolutePath());
    Path tempDirectory = Files.createTempDirectory(tempName);

    AtomicLong lastByte = new AtomicLong(0);
    HashMap<Thread, File> threads = new HashMap<>();
    for (int threadIndex = 1; threadIndex <= this.threadCount; ++threadIndex) {
      long first = 0;
      long last;
      if (threadIndex == this.threadCount) {
        last = fileSize;
        if (threadIndex != 1) {
          first = lastByte.get() + 1;
        }
      } else {
        last = lastByte.get() + oneThreadFileSize;

        if (threadIndex != 1) {
          first = lastByte.get() + 1;
        }
      }
      lastByte.set(last);

      File tempFile = Files.createTempFile(tempDirectory, "Downloader #" + threadIndex, ".bin")
          .toFile();

      DownloadThread downloadThread = new DownloadThread(url, this.userAgent, first, last, tempFile,
          downloadedSize, fileSize, reconnectCount);
      downloadThread.setName("Downloader (" + tempName + ") #" + threadIndex);
      downloadThread.start();
      threads.put(downloadThread, tempFile);
    }

    for (Thread downloader : threads.keySet()) {
      if (!downloader.isInterrupted()) {
        try {
          downloader.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }

    FileOutputStream outputStream = new FileOutputStream(file);
    for (File downloadedFile : threads.values()) {
      if (downloadedFile != null && downloadedFile.exists()) {
        outputStream.write(Files.readAllBytes(downloadedFile.toPath()));
        if (!downloadedFile.delete()) {
          throw new IOException("Ошибка. Невозможно удалить один из временных файлов.");
        }
      } else {
        throw new NullPointerException("Один из скачанных файлов некорректен");
      }
    }

    if (!tempDirectory.toFile().delete()) {
      throw new IOException("Ошибка. Невозможно удалить временную директорию.");
    }

  }

  public long getFileSize(URL url) throws IOException, NullPointerException {
    if (url == null) {
      throw new NullPointerException("Не удалось получить размер файла. Ссылка является пустой.");
    }

    HttpURLConnection connection = null;

    int connectionIndex = 0;
    whileLabel:
    while (connectionIndex < reconnectCount) {
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(10000);
      connection.setRequestProperty("Accept-Charset", "UTF-8");
      connection.setRequestProperty("User-Agent", this.userAgent);
      switch (connection.getResponseCode()) {
        case 301:
        case 302:
          connection.disconnect();
          ++connectionIndex;
          continue;
        default:
          break whileLabel;
      }
    }
    if (connectionIndex > reconnectCount) {
      throw new IOException(
          "Не удалось получить размер файла. Сайт " + url.getHost() + " не отвечает на запросы.");
    }

    return connection != null ? connection.getContentLengthLong() : -1;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public int getThreadCount() {
    return threadCount;
  }

  public int getReconnectCount() {
    return reconnectCount;
  }

  private static class DownloadThread extends Thread {

    private final long firstByte;
    private final long lastByte;
    private final URL url;
    private final File file;
    private final String userAgent;

    private AtomicLong downloadedSize;
    private long fileSize = -1;

    private int reconnectCount = 3;

    public DownloadThread(URL url, String userAgent, long first, long last, File file,
        AtomicLong downloadedSize) {
      this.url = url;
      this.userAgent = userAgent;
      this.firstByte = first;
      this.lastByte = last;
      this.file = file;
      this.downloadedSize = downloadedSize;

    }

    public DownloadThread(URL url, String userAgent, long first, long last, File file,
        AtomicLong downloadedSize, int reconnectCount) {
      this(url, userAgent, first, last, file, downloadedSize);
      this.reconnectCount = reconnectCount;
    }

    public DownloadThread(URL url, String userAgent, long first, long last, File file,
        AtomicLong downloadedSize, long fileSize) {
      this(url, userAgent, first, last, file, downloadedSize);
      this.fileSize = fileSize;
    }

    public DownloadThread(URL url, String userAgent, long first, long last, File file,
        AtomicLong downloadedSize, long fileSize, int reconnectCount) {
      this(url, userAgent, first, last, file, downloadedSize, fileSize);
      this.reconnectCount = reconnectCount;
    }

    @Override
    public void run() {
      try {
        HttpURLConnection connection = null;

        int connectionIndex = 0;
        whileLabel:
        {
          while (connectionIndex < reconnectCount) {
            connection = (HttpURLConnection) this.url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setRequestProperty("Range", "bytes=" + this.firstByte + "-" + this.lastByte);
            connection.setRequestProperty("Accept-Charset", "UTF-8");
            connection.setRequestProperty("User-Agent", this.userAgent);
            switch (connection.getResponseCode()) {
              case 301:
              case 302:
                connection.disconnect();
                ++connectionIndex;
                continue;
              default:
                break whileLabel;
            }
          }
        }

        if (connectionIndex > reconnectCount || connection == null) {
          System.out.println(this.getName() + ". Не удалось установить соединение с сайтом.");
          return;
        }

        BufferedInputStream inputStream = new BufferedInputStream(connection.getInputStream());
        FileOutputStream fileOutput = new FileOutputStream(this.file);

        final byte[] buf = new byte[1024];
        int size;
        while ((size = inputStream.read(buf)) != -1) {
          fileOutput.write(buf, 0, size);
          downloadedSize.addAndGet(size);
          //TODO Обновление прогресса скачивания файла
          System.out.println(
              "Скачано: " + (downloadedSize.get() / 1024) + "/" + (fileSize / 1024) + " KB");
        }
        inputStream.close();
        connection.disconnect();
        fileOutput.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public String getTempFileName(String url, String fileName) {
    return DatatypeConverter.printHexBinary(
        Base64.getEncoder().encode((url + "|" + fileName).getBytes(StandardCharsets.UTF_8)));
  }
}
