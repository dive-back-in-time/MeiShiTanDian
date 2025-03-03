## 项目工具：  
Redis、 SpringBoot、Mybatis、MySQL、Nginx、Postman、JMeter
## 项目描述： 
美食探店是一个高并发、高性能的基于本地生活服务的点评平台，实现了优惠券秒杀、关注、签到统计等功能。项目基于 SpringBoot 搭建后台管理系统，采用redis缓存提升查询性能、提高系统稳定性
## 工作内容： 
- 基于Redis自增实现全局唯一ID，支持每秒 10 万+ID 生成，避免高并发场景下数据库的主键冲突
- 基于Redisson分布式锁解决秒杀超卖问题，优惠券领取成功率提升至 99.99%，提升系统稳定性
- 基于Redis的Stream数据结构实现消息队列，完成异步秒杀业务，削峰限流，降低数据库压力
- 基于Redis的Set、SortedSet、Bitmap数据结构实现点赞列表、点赞排行榜、签到统计业务
