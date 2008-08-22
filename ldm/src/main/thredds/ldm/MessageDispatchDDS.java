/*
 * Copyright 1997-2008 Unidata Program Center/University Corporation for
 * Atmospheric Research, P.O. Box 3000, Boulder, CO 80307,
 * support@unidata.ucar.edu.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package thredds.ldm;

import ucar.bufr.Message;
import ucar.bufr.Dump;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletionService;
import java.nio.channels.WritableByteChannel;
import java.nio.ByteBuffer;

/**
 * BUFR Message Dispatch using DDS hashmap to sort the messages.
 * This is a singleton used on a single thread.
 *
 * @author caron
 * @since Aug 11, 2008
 */
public class MessageDispatchDDS {
  public static final String rootDir = "D:/bufr/";
  public static final String dispatchDir = "D:/bufr/dispatch/";
  private static final String inputFilename = rootDir + "dispatch.csv";
  private static final String inputFilenameOut = rootDir + "dispatchOut.csv";

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MessageDispatchDDS.class);

  private Set<Integer> badHashSet = new HashSet<Integer>(200);
  private Map<String, Integer> nameMap = new HashMap<String, Integer>(200);
  private Map<Integer, MessType> typeMap = new HashMap<Integer, MessType>(200);

  int total_msgs = 0;
  int match = 0;
  int badCount = 0;
  long badBytes = 0;
  int failWrite = 0;
  int ignored = 0;

  long total_bytes;
  int total_obs;

  boolean checkBad = true;
  boolean showMatch = false;
  boolean showBad = false;
  boolean showConfig = false;
  boolean showResults = false;

  Dump dumper = new Dump();
  WritableByteChannel badWbc;
  WritableByteChannel sampleWbc;
  WritableByteChannel allWbc;

  private final CompletionService executor;

  MessageDispatchDDS(CompletionService executor) throws IOException {
    this.executor = executor;

    File inputFile = new File(inputFilename);
    if (inputFile.exists()) {
      BufferedReader dataIS = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile)));
      int count = 0;
      while (true) {
        String line = dataIS.readLine();
        if (line == null) break;
        line = line.trim();
        if (line.length() == 0) break;
        if (line.charAt(0) == '#') continue;

        String[] toke = line.split(",");
        //String hashS = toke[0].substring(2); // remove the "0x"
        Integer hash = Integer.parseInt(toke[0]);
        String bitsOk = (toke.length > 8) ? toke[7].trim() : "";
        typeMap.put(hash, new MessType(hash, toke[1], toke[2], toke[3], bitsOk));
        count++;
      }
    }

    MessageWriter.setRootDir(dispatchDir);

    File file = new File(rootDir + "/dispatch/abad.bufr");
    FileOutputStream fos = new FileOutputStream(file);
    badWbc = fos.getChannel();

    file = new File(rootDir + "/dispatch/asample.bufr");
    fos = new FileOutputStream(file);
    sampleWbc = fos.getChannel();

    file = new File(rootDir + "/dispatch/asampleAll.bufr");
    fos = new FileOutputStream(file);
    allWbc = fos.getChannel();
  }

  void dispatch(Message m) {
    total_msgs++;
    total_bytes += m.getRawBytes().length;
    total_obs += m.getNumberDatasets();

    // find the message type, based on dds
    MessType matched = typeMap.get(m.hashCode());
    boolean newOne = false;

    if (matched == null) {
      matched = new MessType(m);
      typeMap.put(m.hashCode(), matched);
      writeSample(m, allWbc);
      newOne = true;

    } else if (matched.proto == null) {
      matched.proto = m;
      writeSample(m, allWbc);
      newOne = true;
    }

    matched.count++;
    matched.countObs += m.getNumberDatasets();

    if (matched.ignore) {
      ignored++;
      return;
    }

    if (matched.checkBad && checkIfBad(m)) {
      matched.nbad++;
      return; // dont write messages that fail bit counter;
    }

    boolean written = matched.scheduleWrite(m);
    if (written) matched.countBytes += m.getRawBytes().length + m.getHeader().length();

    if (written && newOne) {
      writeSample(m, sampleWbc);  // keep samples
    }
  }

  boolean checkIfBad(Message m) {
    if (!checkBad) return false;

    boolean isBad = false;
    try {
      isBad = m.isTablesComplete() && !m.isBitCountOk();
    } catch (Exception e) {
      isBad = true;
    }
    if (isBad) {
      if (!badHashSet.contains(m.hashCode())) { // only write one kind of each bad message
        badHashSet.add(m.hashCode());
        try {
          badWbc.write(ByteBuffer.wrap(m.getHeader().getBytes()));
          badWbc.write(ByteBuffer.wrap(m.getRawBytes()));

        } catch (IOException e) {
          e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
      }

      if (showBad) System.out.println("bad <" + m.getHeader() + ">");
      badCount++;
      badBytes += m.getRawBytes().length + m.getHeader().length();
    }
    return isBad;
  }

  void writeSample(Message m, WritableByteChannel wbc) {
    try {
      wbc.write(ByteBuffer.wrap(m.getHeader().getBytes()));
      wbc.write(ByteBuffer.wrap(m.getRawBytes()));
    } catch (IOException e) {
      e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
    }
  }

  void exit() {
    try {
      for (MessageWriter writer : writers.values())
        writer.close();
      
      for (MessType mtype : typeMap.values())
        if (mtype.indexer != null) mtype.indexer.close();

      badWbc.close();
      sampleWbc.close();
      allWbc.close();

      Formatter out = new Formatter(System.out);
      Formatter cfg = new Formatter(new FileOutputStream(inputFilenameOut));

      if (showResults) out.format("\n===============================================\n");
      cfg.format("#    hash, filename, wmo, index, nmess, nobs, kBytes, complete, bitsOk, nbad, center, table, edition, category%n");

      int avg_msg = (int) (total_bytes / total_msgs);
      int avg_obs = (int) (total_obs / total_msgs);
      if (showResults) {
        out.format("total_msgs=%d total_obs=%d total_bytes=%d avg_msg_size=%d avg_obs/msg=%d \n",
                total_msgs, total_obs, total_bytes, avg_msg, avg_obs);
        out.format("matched=%d types=%d bad=%d badTypes=%d badBytes=%d ignored=%d failWrite=%d %n",
                match, typeMap.size(), badCount, badHashSet.size(), badBytes, ignored, failWrite);
      }

      List<MessType> mtypes = new ArrayList<MessType>(typeMap.values());
      Collections.sort(mtypes, new MessTypeSorter());
      for (MessType mtype : mtypes) {
        if (mtype.proto == null) {
          if (showResults) out.format(" MessType %s count=%d fileout= %s\n", mtype.name, mtype.count, mtype.fileout);
          cfg.format("%d, %s, %s, %5d, %8d, %5f %n",
                  mtype.hash, mtype.fileout, mtype.name,
                  mtype.count, mtype.countObs, mtype.countBytes / 1000);
          continue;
        }
        Message m = mtype.proto;
        boolean hasBadMessages = badHashSet.contains(m.hashCode()); // did we find any messages that fail bit count ??
        if (showResults) out.format(" MessType %s count=%d fileout= %s\n", mtype.name, mtype.count, mtype.fileout);
        cfg.format("%d, %s, %s, %s, %5d, %8d, %5f, %5s, %5s, %d, %s, %s, %s, %s%n",
                mtype.hash, mtype.fileout, mtype.name, mtype.index,
                mtype.count, mtype.countObs, mtype.countBytes / 1000,
                m.isTablesComplete(), !hasBadMessages, mtype.nbad,
                m.getCenterNo(), m.getTableName(), m.is.getBufrEdition(), m.getCategoryNo());
      }
      if (showResults) out.format("\n");
      cfg.close();
    } catch (IOException ioe) {
      logger.error("MessageDispatchDDS.exit", ioe);
    }
  }

  private class MessType {
    int hash;
    String name;
    String fileout;
    String index;
    BerkeleyDBIndexer indexer;
    int fileno = 0;

    Message proto;
    int count, countObs, nbad;
    float countBytes;
    boolean ignore;
    boolean checkBad = true; // default is to check for bad bits

    MessType(int hash, String filename, String name, String index, String bitsOk) {
      this.hash = hash;
      this.fileout = filename.trim();
      this.name = name.trim();
      this.index = index.trim();

      nameMap.put(this.name, 0);
      this.ignore = fileout.equalsIgnoreCase("ignore");
      if (bitsOk.equalsIgnoreCase("true"))
        this.checkBad = false; // dont need to check bits

      if (index.length() > 0) {
        indexer = BerkeleyDBIndexer.factory( index);  
      }

      if (showConfig) System.out.printf(" add hash=%d name=%s filename=%s index=%s%n", hash, name, fileout, index);
    }

    MessType(Message proto) {
      this.proto = proto;
      this.hash = proto.hashCode();

      this.name = extractName(proto.getHeader());
      Integer nameCount = nameMap.get(this.name);
      if (nameCount != null) {
        nameCount = new Integer(nameCount + 1);
        nameMap.put(this.name, nameCount); // increment this name counter
        this.name = name + "-" + nameCount;  // form a new one to avoid filename collision
      }
      nameMap.put(this.name, 0);

      this.fileout = name;
      this.ignore = fileout.equalsIgnoreCase("ignore");
      System.out.println(" add <" + name + "> fileout= " + fileout);
    }

    String extractName(String header) {
      return header.substring(7, 11) + "-" + header.substring(0, 6);
    }

    boolean scheduleWrite(Message m) {
      Calendar mcal = m.ids.getReferenceTimeCal();
      int day = mcal.get(Calendar.DAY_OF_MONTH);
      String writerName = fileout + "-" + day;
      MessageWriter writer = writers.get(writerName);
      if (writer == null) {
        try {
          writer = new MessageWriter(executor, indexer, fileno, fileout, mcal);
          writers.put(writerName, writer);
          fileno++;
          
        } catch (FileNotFoundException e) {
          e.printStackTrace();
          return false;
        }
      }

      writer.scheduleWrite(m); // put it on the write queue
      if (showMatch) System.out.println("match <" + m.getHeader() + ">");
      match++;

      return true;
    }

  }

  Map<String, MessageWriter> writers = new HashMap<String, MessageWriter>(100);

  private static class MessTypeSorter implements Comparator<MessType> {
    public int compare(MessType o1, MessType o2) {
      //return o2.count - o1.count; // largest first
      return o1.name.compareTo(o2.name);
    }
  }
}
