#!/usr/bin/env ruby
# encoding: utf-8

require 'bunny'
require 'logger'

LOG = Logger.new(STDOUT)
LOG.level = Logger::INFO

#conn = Bunny.new(heartbeat_timeout: 8)
conn = Bunny.new(ENV['RABBITMQ_URL'])
conn.start

ch = conn.create_channel
x  = ch.topic("bunny.examples.recovery.topic", :durable => false)
q  = ch.queue("bunny.examples.recovery.client_named_queue1", :durable => false)

q.bind(x, :routing_key => "abc").bind(x, :routing_key => "def")

loop do
  sleep 2
  data = rand.to_s
  rk   = ["abc", "def"].sample

  begin
    x.publish(data, :routing_key => rk)
    LOG.info "Published #{data}, routing key: #{rk}"
    $stdout.flush
  # happens when a message is published before the connection
  # is recovered
  rescue Exception => e
  end
end
