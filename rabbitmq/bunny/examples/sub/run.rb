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

q.subscribe do |delivery_info, metadata, payload|
  LOG.info "Consumed #{payload}"
  $stdout.flush
end

sleep 1000000