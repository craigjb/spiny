// Simulation model for the Xilinx global buffer, which is a wire as far as
// any of these tests are concerned.
`timescale 1ns / 1ps
module BUFG (input I, output O);
  assign O = I;
endmodule
