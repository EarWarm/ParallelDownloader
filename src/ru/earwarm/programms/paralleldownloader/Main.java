package ru.earwarm.programms.paralleldownloader;

import java.io.File;
import java.io.IOException;
import java.net.URL;

public class Main {

  public static void main(String[] args) {
    int threadCount = 1;
    if (args.length > 0) {
      if (args[0] != null && !args[0].isEmpty()) {
        try {
          threadCount = Integer.parseInt(args[0]);
        } catch (Exception e) {
          System.out.println("Число потоков должно быть в формате целого числа больше 0");
          return;
        }
      }
    }

    String url = "https://speedtest.selectel.ru/1GB";
    Downloader downloader = new Downloader(threadCount, 5);

    try {
      long before = System.currentTimeMillis();
      downloader.download(new URL(url), new File("ParallelDownloader/test"));
      long after = System.currentTimeMillis();
      System.out.println(
          "Время загрузки: " + String.format("%.3f", ((after - before) / 1000.0)) + " сек.");
      System.out.println("Потоков задействовано: " + threadCount);
      System.out
          .println("Число логических процессоров: " + Runtime.getRuntime().availableProcessors());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
