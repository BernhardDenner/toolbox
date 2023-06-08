#!/usr/bin/env ruby

require 'socket'
require 'logger'
require 'date'

# flush stdout after each line
STDOUT.sync = true
LOG = Logger.new(STDOUT)
LOG.level = Logger::INFO

LOG.info 'starting UDPservice...'

if ENV.fetch("START_SLOW", "") == "true"
  10.times do
    puts "still loading stuff ..."
    sleep 2
  end
end

PORT = ENV.fetch 'PORT', "9876"

LOG.info "starting UDP socket on port #{PORT}"
socket = UDPSocket.new
socket.bind("0.0.0.0", PORT.to_i)

while true do
  resp = socket.recvfrom(256)
  msg = resp[0]
  remote = resp[1][2]
  LOG.info "got message from #{remote}: #{msg}"
end

