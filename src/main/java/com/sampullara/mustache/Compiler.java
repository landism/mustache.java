package com.sampullara.mustache;

import java.io.*;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Mustache.java runtime compiler.
 * <p/>
 * User: sam
 * Date: May 3, 2010
 * Time: 10:00:07 AM
 */
public class Compiler {
  private File root;
  private static String header, middle, footer;
  private static AtomicInteger num = new AtomicInteger(0);
  private Logger logger = Logger.getLogger(getClass().getName());

  static {
    header = getText("/header.txt");
    middle = getText("/middle.txt");
    footer = getText("/footer.txt");
  }

  private static String getText(String template) {
    InputStream stream = Compiler.class.getResourceAsStream(template);
    BufferedReader br = new BufferedReader(new InputStreamReader(stream));
    return getText(template, br);
  }

  private static String getText(String template, BufferedReader br) {
    StringBuilder text = new StringBuilder();
    String line;
    try {
      while ((line = br.readLine()) != null) {
        text.append(line);
      }
      br.close();
    } catch (IOException e) {
      throw new AssertionError("Failed to read template file: " + template);
    }
    return text.toString();
  }

  public Compiler() {
    this.root = new File(".");
  }

  public Compiler(File root) {
    this.root = root;
  }

  private Map<File, Mustache> filecache = new ConcurrentHashMap<File, Mustache>();
  private Map<String, Mustache> partialcache = new ConcurrentHashMap<String, Mustache>();

  public synchronized Mustache parse(String partial) throws MustacheException {
    AtomicInteger currentLine = new AtomicInteger(0);
    Mustache result = partialcache.get(partial);
    if (result == null) {
      BufferedReader br = new BufferedReader(new StringReader(partial));
      result = compile(br, new Stack<String>(), currentLine, null);
      partialcache.put(partial, result);
    }
    return result;
  }

  public synchronized Mustache parseFile(String path) throws MustacheException {
    AtomicInteger currentLine = new AtomicInteger(0);
    File file = new File(root, path);
    Mustache result = filecache.get(file);
    if (result == null) {
      BufferedReader br;
      try {
        br = new BufferedReader(new FileReader(file));
      } catch (FileNotFoundException e) {
        throw new MustacheException("Mustache file not found: " + file);
      }
      result = compile(br, new Stack<String>(), currentLine, null);
      filecache.put(file, result);
    }
    return result;
  }

  public Mustache compile(BufferedReader br, Stack<String> scope, AtomicInteger currentline, ClassLoader parent) throws MustacheException {
    Mustache result;
    StringBuilder sb = new StringBuilder();
    sb.append(header);
    String className = "Mustache" + num.getAndIncrement();
    sb.append(className);
    sb.append(middle);
    // Now we grab the mustache template
    String startMustache = "{{";
    String endMustache = "}}";
    String line;
    try {
      br.mark(1024);
      READ:
      while ((line = br.readLine()) != null) {
        currentline.incrementAndGet();
        int last = 0;
        int foundStart;
        boolean tagonly = false;
        line = line.trim();
        while ((foundStart = line.indexOf(startMustache)) != -1) {
          int foundEnd = line.indexOf(endMustache);
          // Look for the 3rd ending mustache
          if (line.length() > foundEnd + 2 && line.charAt(foundEnd + 2) == '}') {
            foundEnd++;
          }
          // Unterminated mustache
          if (foundEnd < foundStart) {
            throw new MustacheException("Found unmatched end mustache: " + currentline + ":" + foundEnd);
          }
          // If there is only a tag on a line, don't insert a newline
          if (foundStart == 0 && foundEnd + endMustache.length() == line.length()) {
            tagonly = true;
          }
          String pre = line.substring(last, foundStart);
          writeText(sb, pre, false);
          String command = line.substring(foundStart + startMustache.length(), foundEnd);
          switch (command.charAt(0)) {
            case '!':
              // Comment, do nothing with the content
              break;
            case '#':
              // Tag start
              String startTag = command.substring(1).trim();
              scope.push(startTag);
              undo(br, foundEnd + (foundEnd + 2 == line.length() ? 1 : 0));
              Mustache sub = compile(br, scope, currentline, parent);
              parent = sub.getClass().getClassLoader();
              sb.append("for (Scope s").append(num.incrementAndGet());
              sb.append(":iterable(s, \"");
              sb.append(startTag);
              sb.append("\")) {");
              sb.append("new ").append(sub.getClass().getName());
              sb.append("().execute(w, s").append(num.get()).append(");");
              sb.append("}");
              continue READ;
            case '^':
              // Inverted tag
              startTag = command.substring(1).trim();
              scope.push(startTag);
              undo(br, foundEnd + (foundEnd + 2 == line.length() ? 1 : 0));
              sub = compile(br, scope, currentline, parent);
              parent = sub.getClass().getClassLoader();
              sb.append("for (Scope s").append(num.incrementAndGet());
              sb.append(":inverted(s, \"");
              sb.append(startTag);
              sb.append("\")) {");
              sb.append("new ").append(sub.getClass().getName());
              sb.append("().execute(w, s").append(num.get()).append(");");
              sb.append("}");
              continue READ;
            case '/':
              // Tag end
              String endTag = command.substring(1).trim();
              String expected = scope.pop();
              if (!endTag.equals(expected)) {
                throw new MustacheException("Mismatched start/end tags: " + expected + " != " + endTag + " at " + currentline);
              }
              break READ;
            case '>':
              // Partial
              String partialName = command.substring(1).trim();
              sb.append("compile(s, \"").append(partialName).append("\").execute(w,s);");
              break;
            case '{':
              // Not escaped
              if (command.endsWith("}")) {
                sb.append("write(w, s, \"").append(command.substring(1, command.length() - 1).trim()).append("\", false);");
              } else {
                throw new MustacheException("Unescaped section not terminated properly: " + command + " at " + currentline + ":" + foundStart);
              }
              break;
            case '&':
              // Not escaped
              sb.append("write(w, s, \"").append(command.substring(1).trim()).append("\", false);");
              break;
            case '%':
              // Pragmas
              break;
            default:
              // Reference
              sb.append("write(w, s, \"").append(command.trim()).append("\", true);");
              break;
          }
          line = line.substring(foundEnd + endMustache.length());
          br.mark(1024);
        }
        if (!tagonly) writeText(sb, last == 0 ? line : line.substring(last), true);
      }
    } catch (IOException e) {
      throw new MustacheException("Failed to read template: " + e);
    }
    sb.append(footer);
    try {
      String code = sb.toString();
      ClassLoader loader = RuntimeJavaCompiler.compile(new PrintWriter(System.out, true), className, code, parent);
      Class<?> aClass = loader.loadClass("com.sampullara.mustaches." + className);
      result = (Mustache) aClass.newInstance();
    } catch (Exception e) {
      e.printStackTrace();
      throw new MustacheException("Failed to compile code: " + e);
    }
    return result;
  }

  private void undo(BufferedReader br, int foundEnd) throws IOException {
    br.reset();
    int total = foundEnd + 2;
    while (total > 0) {
      int done = br.read(new char[total]);
      if (done == -1) {
        break;
      }
      total -= done;
    }
    br.mark(1024);
  }

  private void writeText(StringBuilder sb, String text, boolean endline) {
    text = text.replaceAll("\"", "\\\"");
    sb.append("w.write(\"").append(text);
    if (endline) sb.append("\\n");
    sb.append("\");\n");
  }
}
