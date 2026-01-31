###########################################################
# Timing                                                  #
###########################################################

# 100 MHz clock input on board
create_clock \
  -period 10.0 \
  -name SYS_CLK \
  [get_ports SYS_CLK]

# internal BSCANE2 JTAG up to 50 MHz
# create_clock \
#   -period 20.000 \
#   -name JTAG_CLK \
#   [get_pins -hierarchical *jtagTap/TCK]

# clocks are not related
# set_clock_groups \
#   -asynchronous \
#   -group [get_clocks -include_generated_clocks SYS_CLK] \
#   -group [get_clocks -include_generated_clocks JTAG_CLK]

set_false_path -from [get_ports { CPU_RESET_N }]
set_false_path -to [get_ports { LEDS[*] }]
set_false_path -from [get_ports { SWITCHES[*] }]

###########################################################
# Pins                                                    #
###########################################################
set_property -dict { \
  PACKAGE_PIN R4 \
  IOSTANDARD LVCMOS33 \
} [get_ports { SYS_CLK }];
set_property -dict { \
  PACKAGE_PIN G4 \
  IOSTANDARD LVCMOS15 \
} [get_ports { CPU_RESET_N }];

set_property -dict { \
  PACKAGE_PIN T14 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[0] }];
set_property -dict { \
  PACKAGE_PIN T15 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[1] }];
set_property -dict { \
  PACKAGE_PIN T16 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[2] }];
set_property -dict { \
  PACKAGE_PIN U16 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[3] }];
set_property -dict { \
  PACKAGE_PIN V15 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[4] }];
set_property -dict { \
  PACKAGE_PIN W16 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[5] }];
set_property -dict { \
  PACKAGE_PIN W15 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[6] }];
set_property -dict { \
  PACKAGE_PIN Y13 \
  IOSTANDARD LVCMOS25 \
} [get_ports { LEDS[7] }];

set_property -dict { \
  PACKAGE_PIN E22 \
  IOSTANDARD LVCMOS12 \
} [get_ports { SWITCHES[0] }];
set_property -dict { \
  PACKAGE_PIN F21 \
  IOSTANDARD LVCMOS12 \
} [get_ports { SWITCHES[1] }];
set_property -dict { \
  PACKAGE_PIN G21 \
  IOSTANDARD LVCMOS12 \
} [get_ports { SWITCHES[2] }];
set_property -dict { \
  PACKAGE_PIN G22 \
  IOSTANDARD LVCMOS12 \
} [get_ports { SWITCHES[3] }];
set_property -dict { \
  PACKAGE_PIN H17 \
  IOSTANDARD LVCMOS12 \
} [get_ports { SWITCHES[4] }];
set_property -dict { \
  PACKAGE_PIN J16 \
  IOSTANDARD LVCMOS12 \
} [get_ports { SWITCHES[5] }];
set_property -dict { \
  PACKAGE_PIN K13 \
  IOSTANDARD LVCMOS12 \
} [get_ports { SWITCHES[6] }];
set_property -dict { \
  PACKAGE_PIN M17 \
  IOSTANDARD LVCMOS12 \
} [get_ports { SWITCHES[7] }];

###########################################################
# Config Voltage                                          #
###########################################################
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
