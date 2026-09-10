// 权限 / 用户 / 角色类型定义

export interface UserMeta {
  id: string;
  username: string;
  display_name: string;
  tenant_id: string;
  enabled: boolean;
  created_at: string;
}

export interface RoleMeta {
  id: string;
  name: string;
  description: string;
  tenant_id: string;
  created_at: string;
}

export type AclType = 'FIELD' | 'ROW' | 'ACTION';
export type AclAction = 'CREATE' | 'READ' | 'UPDATE' | 'DELETE';

export interface AclPolicy {
  id: string;
  role_id: string;
  type: AclType;
  subject: string;
  action: AclAction | null;
  config_json: string;
  tenant_id: string;
  created_at: string;
}

export interface EffectivePermissions {
  user_id: string;
  roles: RoleMeta[];
  policies_summary: Array<{ role_id: string; role_name: string }>;
}
