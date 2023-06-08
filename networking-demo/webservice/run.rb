#!/usr/bin/env ruby

require 'webrick'
require 'logger'
require 'date'

# flush stdout after each line
STDOUT.sync = true
LOG = Logger.new(STDOUT)
LOG.level = Logger::INFO

LOG.info 'starting webservice...'

if ENV.fetch("START_SLOW", "") == "true"
  30.times do
    puts "still loading stuff ..."
    sleep 2
  end
end

PORT = ENV.fetch 'PORT', "8080"

server = WEBrick::HTTPServer.new :Port => PORT

server.mount_proc '/' do |req, res|
  message = "#{DateTime.now} Hello from webservice 💪 , "
  message += "Remote IP: #{req.remote_ip}, "
  message += "hostname: #{ENV.fetch 'HOSTNAME', ''}, "
  message += "POD_IP: #{ENV.fetch 'POD_IP', ''}"

  case  req['Accept']
  when /.*html.*/
    res.body = "<html><body><pre>#{message.gsub ', ', "\n"}</body></html>"
  else
    res.body = message + "\n"
  end
end

trap 'INT' do
  server.shutdown
end

server.start
