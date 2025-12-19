create_clock -period 10.0 -name SYS_CLK [get_ports SYS_CLK]

set_property -dict { \
  PACKAGE_PIN E3 \
  IOSTANDARD LVCMOS33 \
} [get_ports { SYSCLK }];

set_property -dict { \
  PACKAGE_PIN C12 \
  IOSTANDARD LVCMOS33 \
} [get_ports { CPU_RESET_N }];

set_property -dict { \
  PACKAGE_PIN H17 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[0] }];
set_property -dict { \
  PACKAGE_PIN K15 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[1] }];
set_property -dict { \
  PACKAGE_PIN J13 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[2] }];
set_property -dict { \
  PACKAGE_PIN N14 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[3] }];
set_property -dict { \
  PACKAGE_PIN R18 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[4] }];
set_property -dict { \
  PACKAGE_PIN V17 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[5] }];
set_property -dict { \
  PACKAGE_PIN U17 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[6] }];
set_property -dict { \
  PACKAGE_PIN U16 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[7] }];
set_property -dict { \
  PACKAGE_PIN V16 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[8] }];
set_property -dict { \
  PACKAGE_PIN T15 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[9] }];
set_property -dict { \
  PACKAGE_PIN U14 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[10] }];
set_property -dict { \
  PACKAGE_PIN T16 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[11] }];
set_property -dict { \
  PACKAGE_PIN V15 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[12] }];
set_property -dict { \
  PACKAGE_PIN V14 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[13] }];
set_property -dict { \
  PACKAGE_PIN V12 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[14] }];
set_property -dict { \
  PACKAGE_PIN V11 \
  IOSTANDARD LVCMOS33 \
} [get_ports { LEDS[15] }];
