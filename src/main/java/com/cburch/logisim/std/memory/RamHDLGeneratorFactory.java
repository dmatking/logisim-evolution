/*
 * This file is part of logisim-evolution.
 *
 * Logisim-evolution is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Logisim-evolution is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with logisim-evolution. If not, see <http://www.gnu.org/licenses/>.
 *
 * Original code by Carl Burch (http://www.cburch.com), 2011.
 * Subsequent modifications by:
 *   + College of the Holy Cross
 *     http://www.holycross.edu
 *   + Haute École Spécialisée Bernoise/Berner Fachhochschule
 *     http://www.bfh.ch
 *   + Haute École du paysage, d'ingénierie et d'architecture de Genève
 *     http://hepia.hesge.ch/
 *   + Haute École d'Ingénierie et de Gestion du Canton de Vaud
 *     http://www.heig-vd.ch/
 */

package com.cburch.logisim.std.memory;

import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.fpga.designrulecheck.Netlist;
import com.cburch.logisim.fpga.designrulecheck.NetlistComponent;
import com.cburch.logisim.fpga.gui.Reporter;
import com.cburch.logisim.fpga.hdlgenerator.AbstractHDLGeneratorFactory;
import com.cburch.logisim.fpga.hdlgenerator.HDL;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.ClockHDLGeneratorFactory;
import java.util.ArrayList;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public class RamHDLGeneratorFactory extends AbstractHDLGeneratorFactory {

  private static final String ByteArrayStr = "BYTE_ARRAY";
  private static final int ByteArrayId = -1;
  private static final String RestArrayStr = "REST_ARRAY";
  private static final int RestArrayId = -2;
  private static final String MemArrayStr = "MEMORY_ARRAY";
  private static final int MemArrayId = -3;

  @Override
  public String getComponentStringIdentifier() {
    return "RAM";
  }

  @Override
  public SortedMap<String, Integer> GetInputList(Netlist nets, AttributeSet attrs) {
    SortedMap<String, Integer> inputs = new TreeMap<>();
    final var nrOfBits = attrs.getValue(Mem.DATA_ATTR).getWidth();
    inputs.put("Address", attrs.getValue(Mem.ADDR_ATTR).getWidth());
    inputs.put("DataIn", nrOfBits);
    inputs.put("WE", 1);
    inputs.put("OE", 1);
    Object trigger = attrs.getValue(StdAttr.TRIGGER);
    final var asynch = trigger.equals(StdAttr.TRIG_HIGH) || trigger.equals(StdAttr.TRIG_LOW);
    if (!asynch) {
      inputs.put("Clock", 1);
      inputs.put("Tick", 1);
    }
    Object be = attrs.getValue(RamAttributes.ATTR_ByteEnables);
    final var byteEnables = be != null && be.equals(RamAttributes.BUS_WITH_BYTEENABLES);
    if (byteEnables) {
      final var nrOfByteEnables = RamAppearance.getNrBEPorts(attrs);
      for (var i = 0; i < nrOfByteEnables; i++) {
        inputs.put("ByteEnable" + i, 1);
      }
    }
    return inputs;
  }

  @Override
  public SortedMap<String, Integer> GetMemList(AttributeSet attrs) {
    SortedMap<String, Integer> mems = new TreeMap<>();
    if (HDL.isVHDL()) {
      Object be = attrs.getValue(RamAttributes.ATTR_ByteEnables);
      final var byteEnables = be != null && be.equals(RamAttributes.BUS_WITH_BYTEENABLES);
      int nrOfBits = attrs.getValue(Mem.DATA_ATTR).getWidth();
      if (byteEnables) {
        final var truncated = (nrOfBits % 8) != 0;
        var nrOfByteEnables = RamAppearance.getNrBEPorts(attrs);
        if (truncated) {
          nrOfByteEnables--;
          mems.put("s_trunc_mem_contents", RestArrayId);
        }
        for (int i = 0; i < nrOfByteEnables; i++) {
          mems.put("s_byte_mem_" + i + "_contents", ByteArrayId);
        }
      } else {
        mems.put("s_mem_contents", MemArrayId);
      }
    }
    return mems;
  }

  @Override
  public ArrayList<String> GetModuleFunctionality(Netlist TheNetlist, AttributeSet attrs) {
    final var contents = new ArrayList<String>();
    Object be = attrs.getValue(RamAttributes.ATTR_ByteEnables);
    final var byteEnables = be != null && be.equals(RamAttributes.BUS_WITH_BYTEENABLES);
    if (HDL.isVHDL()) {
      contents.addAll(MakeRemarkBlock("Here the control signals are defined", 3));
      if (byteEnables) {
        for (var i = 0; i < RamAppearance.getNrBEPorts(attrs); i++) {
          contents.add(
              "   s_byte_enable_"
                  + i
                  + " <= s_ByteEnableReg("
                  + i
                  + ") AND s_TickDelayLine(2) AND s_OEReg;");
          contents.add(
              "   s_we_"
                  + i
                  + "          <= s_ByteEnableReg("
                  + i
                  + ") AND s_TickDelayLine(0) AND s_WEReg;");
        }
      } else {
        contents.add("   s_oe <= s_TickDelayLine(2) AND s_OEReg;");
        contents.add("   s_we <= s_TickDelayLine(0) AND s_WEReg;");
      }
      contents.add("");
      contents.addAll(MakeRemarkBlock("Here the input registers are defined", 3));
      contents.add("   InputRegs : PROCESS (Clock , Tick , Address , DataIn , WE , OE )");
      contents.add("   BEGIN");
      contents.add("      IF (Clock'event AND (Clock = '1')) THEN");
      contents.add("         IF (Tick = '1') THEN");
      contents.add("             s_DataInReg        <= DataIn;");
      contents.add("             s_Address_reg      <= Address;");
      contents.add("             s_WEReg            <= WE;");
      contents.add("             s_OEReg            <= OE;");
      if (byteEnables) {
        for (var i = 0; i < RamAppearance.getNrBEPorts(attrs); i++) {
          contents.add(
              "             s_ByteEnableReg("
                  + i
                  + ") <= ByteEnable"
                  + i
                  + ";");
        }
      }
      contents.add("         END IF;");
      contents.add("      END IF;");
      contents.add("   END PROCESS InputRegs;");
      contents.add("");
      contents.add("   TickPipeReg : PROCESS(Clock)");
      contents.add("   BEGIN");
      contents.add("      IF (Clock'event AND (Clock = '1')) THEN");
      contents.add("          s_TickDelayLine(0)          <= Tick;");
      contents.add("          s_TickDelayLine(2 DOWNTO 1) <= s_TickDelayLine(1 DOWNTO 0);");
      contents.add("      END IF;");
      contents.add("   END PROCESS TickPipeReg;");
      contents.add("");
      contents.addAll(MakeRemarkBlock("Here the actual memorie(s) is(are) defined", 3));
      if (byteEnables) {
        final var truncated = (attrs.getValue(Mem.DATA_ATTR).getWidth() % 8) != 0;
        for (var i = 0; i < RamAppearance.getNrBEPorts(attrs); i++) {
          contents.add(
              "   Mem"
                  + i
                  + " : PROCESS( Clock , s_we_"
                  + i
                  + ", s_DataInReg, s_Address_reg)");
          contents.add("   BEGIN");
          contents.add("      IF (Clock'event AND (Clock = '1')) THEN");
          contents.add("            IF (s_we_" + i + " = '1') THEN");
          final var startIndex = i * 8;
          final var endIndex =
              (i == (RamAppearance.getNrBEPorts(attrs) - 1))
                  ? attrs.getValue(Mem.DATA_ATTR).getWidth() - 1
                  : (i + 1) * 8 - 1;
          final var memName =
              (i == (RamAppearance.getNrBEPorts(attrs) - 1) && truncated)
                  ? "s_trunc_mem_contents"
                  : "s_byte_mem_" + i + "_contents";
          contents.add(
              "               "
                  + memName
                  + "(to_integer(unsigned(s_Address_reg))) <= s_DataInReg("
                  + endIndex
                  + " DOWNTO "
                  + startIndex
                  + ");");
          contents.add("            END IF;");
          contents.add(
              "            s_ram_data_out("
                  + endIndex
                  + " DOWNTO "
                  + startIndex
                  + ") <= "
                  + memName
                  + "(to_integer(unsigned(s_Address_reg)));");
          contents.add("      END IF;");
          contents.add("   END PROCESS Mem" + i + ";");
          contents.add("");
        }
      } else {
        contents.add("   Mem : PROCESS( Clock , s_we, s_DataInReg, s_Address_reg)");
        contents.add("   BEGIN");
        contents.add("      IF (Clock'event AND (Clock = '1')) THEN");
        contents.add("            IF (s_we = '1') THEN");
        contents.add(
            "               s_mem_contents(to_integer(unsigned(s_Address_reg))) <= s_DataInReg;");
        contents.add("            END IF;");
        contents.add(
            "            s_ram_data_out <= s_mem_contents(to_integer(unsigned(s_Address_reg)));");
        contents.add("      END IF;");
        contents.add("   END PROCESS Mem;");
        contents.add("");
      }
      contents.addAll(MakeRemarkBlock("Here the output register is defined", 3));
      if (byteEnables) {
        for (var i = 0; i < RamAppearance.getNrBEPorts(attrs); i++) {
          contents.add(
              "   Res"
                  + i
                  + " : PROCESS( Clock , s_byte_enable_"
                  + i
                  + ", s_ram_data_out)");
          contents.add("   BEGIN");
          contents.add("      IF (Clock'event AND (Clock = '1')) THEN");
          contents.add("         IF (s_byte_enable_" + i + " = '1') THEN");
          final var startIndex = i * 8;
          final var endIndex =
              (i == (RamAppearance.getNrBEPorts(attrs) - 1))
                  ? attrs.getValue(Mem.DATA_ATTR).getWidth() - 1
                  : (i + 1) * 8 - 1;
          contents.add(
              "           DataOut("
                  + endIndex
                  + " DOWNTO "
                  + startIndex
                  + ") <= s_ram_data_out("
                  + endIndex
                  + " DOWNTO "
                  + startIndex
                  + ");");
          contents.add("         END IF;");
          contents.add("      END IF;");
          contents.add("   END PROCESS Res" + i + ";");
          contents.add("");
        }
      } else {
        contents.add("   Res : PROCESS( Clock , s_oe, s_ram_data_out)");
        contents.add("   BEGIN");
        contents.add("      IF (Clock'event AND (Clock = '1')) THEN");
        contents.add("         IF (s_oe = '1') THEN");
        contents.add("           DataOut <= s_ram_data_out;");
        contents.add("         END IF;");
        contents.add("      END IF;");
        contents.add("   END PROCESS Res;");
        contents.add("");
      }
    }
    return contents;
  }

  @Override
  public int GetNrOfTypes(Netlist nets, AttributeSet attrs) {
    Object be = attrs.getValue(RamAttributes.ATTR_ByteEnables);
    final var byteEnables = be != null && be.equals(RamAttributes.BUS_WITH_BYTEENABLES);
    final var nrOfBits = attrs.getValue(Mem.DATA_ATTR).getWidth();
    return (byteEnables) ? ((nrOfBits % 8) == 0) ? 1 : 2 : 1;
  }

  @Override
  public SortedMap<String, Integer> GetOutputList(Netlist TheNetlist, AttributeSet attrs) {
    SortedMap<String, Integer> outputs = new TreeMap<>();
    outputs.put("DataOut", attrs.getValue(Mem.DATA_ATTR).getWidth());
    return outputs;
  }

  @Override
  public SortedMap<String, String> GetPortMap(Netlist nets, Object mapInfo) {
    SortedMap<String, String> portMap = new TreeMap<>();
    if (!(mapInfo instanceof NetlistComponent)) return portMap;
    final var componentInfo = (NetlistComponent) mapInfo;
    final var attrs = componentInfo.GetComponent().getAttributeSet();
    Object trigger = attrs.getValue(StdAttr.TRIGGER);
    final var asynch = trigger.equals(StdAttr.TRIG_HIGH) || trigger.equals(StdAttr.TRIG_LOW);
    Object be = attrs.getValue(RamAttributes.ATTR_ByteEnables);
    final var byteEnables = be != null && be.equals(RamAttributes.BUS_WITH_BYTEENABLES);
    portMap.putAll(GetNetMap("Address", true, componentInfo, RamAppearance.getAddrIndex(0, attrs), nets));
    final var dinPin = RamAppearance.getDataInIndex(0, attrs);
    portMap.putAll(GetNetMap("DataIn", true, componentInfo, dinPin, nets));
    portMap.putAll(GetNetMap("WE", true, componentInfo, RamAppearance.getWEIndex(0, attrs), nets));
    portMap.putAll(GetNetMap("OE", true, componentInfo, RamAppearance.getOEIndex(0, attrs), nets));
    if (!asynch) {
      if (!componentInfo.EndIsConnected(RamAppearance.getClkIndex(0, attrs))) {
        Reporter.Report.AddError(
            "Component \"RAM\" in circuit \""
                + nets.getCircuitName()
                + "\" has no clock connection!");
        portMap.put("Clock", HDL.zeroBit());
        portMap.put("Tick", HDL.zeroBit());
      } else {
        final var clockNetName = GetClockNetName(componentInfo, RamAppearance.getClkIndex(0, attrs), nets);
        if (clockNetName.isEmpty()) {
          portMap.putAll(GetNetMap("Clock", true, componentInfo, RamAppearance.getClkIndex(0, attrs), nets));
          portMap.put("Tick", HDL.oneBit());
        } else {
          int clockBusIndex;
          if (nets.RequiresGlobalClockConnection()) {
            clockBusIndex = ClockHDLGeneratorFactory.GlobalClockIndex;
          } else {
            clockBusIndex =
                (attrs.getValue(StdAttr.TRIGGER) == StdAttr.TRIG_RISING)
                    ? ClockHDLGeneratorFactory.PositiveEdgeTickIndex
                    : ClockHDLGeneratorFactory.NegativeEdgeTickIndex;
          }

          portMap.put(
              "Clock",
              clockNetName
                  + HDL.BracketOpen()
                  + ClockHDLGeneratorFactory.GlobalClockIndex
                  + HDL.BracketClose());
          portMap.put("Tick", clockNetName + HDL.BracketOpen() + clockBusIndex + HDL.BracketClose());
        }
      }
    }
    if (byteEnables) {
      final var nrOfByteEnables = RamAppearance.getNrBEPorts(componentInfo.GetComponent().getAttributeSet());
      final var byteEnableOffset = RamAppearance.getBEIndex(0, componentInfo.GetComponent().getAttributeSet());
      for (var i = 0; i < nrOfByteEnables; i++) {
        portMap.putAll(
            GetNetMap(
                "ByteEnable" + i,
                false,
                componentInfo,
                byteEnableOffset + nrOfByteEnables - i - 1,
                nets));
      }
    }
    portMap.putAll(GetNetMap("DataOut", true, componentInfo, RamAppearance.getDataOutIndex(0, attrs), nets));
    return portMap;
  }

  @Override
  public SortedMap<String, Integer> GetRegList(AttributeSet attrs) {
    SortedMap<String, Integer> regs = new TreeMap<>();
    Object be = attrs.getValue(RamAttributes.ATTR_ByteEnables);
    final var byteEnables = be != null && be.equals(RamAttributes.BUS_WITH_BYTEENABLES);
    final var nrOfBits = attrs.getValue(Mem.DATA_ATTR).getWidth();
    final var nrOfAddressLines = attrs.getValue(Mem.ADDR_ATTR).getWidth();
    regs.put("s_TickDelayLine", 3);
    regs.put("s_DataInReg", nrOfBits);
    regs.put("s_Address_reg", nrOfAddressLines);
    regs.put("s_WEReg", 1);
    regs.put("s_OEReg", 1);
    regs.put("s_DataOutReg", nrOfBits);
    if (byteEnables) {
      regs.put("s_ByteEnableReg", RamAppearance.getNrBEPorts(attrs));
    }
    return regs;
  }

  @Override
  public String GetSubDir() {
    return "memory";
  }

  @Override
  public String GetType(int TypeNr) {
    switch (TypeNr) {
      case MemArrayId:
        return MemArrayStr;
      case ByteArrayId:
        return ByteArrayStr;
      case RestArrayId:
        return RestArrayStr;
    }
    return "";
  }

  @Override
  public SortedSet<String> GetTypeDefinitions(Netlist TheNetlist, AttributeSet attrs) {
    SortedSet<String> myTypes = new TreeSet<>();
    if (HDL.isVHDL()) {
      Object be = attrs.getValue(RamAttributes.ATTR_ByteEnables);
      final var byteEnables = be != null && be.equals(RamAttributes.BUS_WITH_BYTEENABLES);
      final var nrOfBits = attrs.getValue(Mem.DATA_ATTR).getWidth();
      final var nrOfAddressLines = attrs.getValue(Mem.ADDR_ATTR).getWidth();
      final var ramEntries = (1 << nrOfAddressLines);
      if (byteEnables) {
        myTypes.add(
            "TYPE "
                + ByteArrayStr
                + " IS ARRAY ("
                + (ramEntries - 1)
                + " DOWNTO 0) OF std_logic_vector(7 DOWNTO 0)");
        if ((nrOfBits % 8) != 0) {
          myTypes.add(
              "TYPE "
                  + RestArrayStr
                  + " IS ARRAY ("
                  + (ramEntries - 1)
                  + " DOWNTO 0) OF std_logic_vector("
                  + ((nrOfBits % 8) - 1)
                  + " DOWNTO 0)");
        }
      } else {
        myTypes.add(
            "TYPE "
                + MemArrayStr
                + " IS ARRAY ("
                + (ramEntries - 1)
                + " DOWNTO 0) OF std_logic_vector("
                + (nrOfBits - 1)
                + " DOWNTO 0)");
      }
    }
    return myTypes;
  }

  @Override
  public SortedMap<String, Integer> GetWireList(AttributeSet attrs, Netlist Nets) {
    SortedMap<String, Integer> wires = new TreeMap<>();
    final var nrOfBits = attrs.getValue(Mem.DATA_ATTR).getWidth();
    Object be = attrs.getValue(RamAttributes.ATTR_ByteEnables);
    final var byteEnables = be != null && be.equals(RamAttributes.BUS_WITH_BYTEENABLES);
    wires.put("s_ram_data_out", nrOfBits);
    if (byteEnables) {
      for (var i = 0; i < RamAppearance.getNrBEPorts(attrs); i++) {
        wires.put("s_byte_enable_" + i, 1);
        wires.put("s_we_" + i, 1);
      }
    } else {
      wires.put("s_we", 1);
      wires.put("s_oe", 1);
    }
    return wires;
  }

  @Override
  public boolean HDLTargetSupported(AttributeSet attrs) {
    if (attrs == null) return false;
    Object busVal = attrs.getValue(RamAttributes.ATTR_DBUS);
    final var separate = busVal != null && busVal.equals(RamAttributes.BUS_SEP);
    Object trigger = attrs.getValue(StdAttr.TRIGGER);
    final var asynch = trigger == null || trigger.equals(StdAttr.TRIG_HIGH) || trigger.equals(StdAttr.TRIG_LOW);
    final var byteEnabled = RamAppearance.getNrLEPorts(attrs) == 0;
    final var syncRead = !attrs.containsAttribute(Mem.ASYNC_READ) || !attrs.getValue(Mem.ASYNC_READ);
    final var clearPin = attrs.getValue(RamAttributes.CLEAR_PIN) == null ? false : attrs.getValue(RamAttributes.CLEAR_PIN);
    final var readAfterWrite = !attrs.containsAttribute(Mem.READ_ATTR) || attrs.getValue(Mem.READ_ATTR).equals(Mem.READAFTERWRITE);
    return HDL.isVHDL() && separate && !asynch && byteEnabled && syncRead && !clearPin && readAfterWrite;
  }
}
